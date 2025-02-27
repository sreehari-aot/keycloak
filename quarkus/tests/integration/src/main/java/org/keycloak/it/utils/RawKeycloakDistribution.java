/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.it.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.quarkus.fs.util.ZipUtils;
import org.apache.commons.io.FileUtils;

import org.keycloak.common.Version;
import org.keycloak.quarkus.runtime.Environment;
import org.keycloak.quarkus.runtime.cli.command.Build;
import org.keycloak.quarkus.runtime.configuration.mappers.PropertyMapper;
import org.keycloak.quarkus.runtime.configuration.mappers.PropertyMappers;

import static org.keycloak.quarkus.runtime.Environment.LAUNCH_MODE;

public final class RawKeycloakDistribution implements KeycloakDistribution {

    private Process keycloak;
    private int exitCode = -1;
    private final Path distPath;
    private final List<String> outputStream = new ArrayList<>();
    private final List<String> errorStream = new ArrayList<>();
    private boolean manualStop;
    private String relativePath;
    private int httpPort;
    private boolean debug;
    private boolean reCreate;
    private boolean removeBuildOptionsAfterBuild;
    private ExecutorService outputExecutor;
    private boolean inited = false;

    public RawKeycloakDistribution(boolean debug, boolean manualStop, boolean reCreate, boolean removeBuildOptionsAfterBuild) {
        this.debug = debug;
        this.manualStop = manualStop;
        this.reCreate = reCreate;
        this.removeBuildOptionsAfterBuild = removeBuildOptionsAfterBuild;
        this.distPath = prepareDistribution();
    }

    @Override
    public void start(List<String> arguments) {
        reset();
        if (manualStop && isRunning()) {
            throw new IllegalStateException("Server already running. You should manually stop the server before starting it again.");
        }
        stop();
        try {
            startServer(arguments);
            if (manualStop) {
                asyncReadOutput();
                waitForReadiness();
            } else {
                readOutput();
            }
        } catch (Exception cause) {
            stop();
            throw new RuntimeException("Failed to start the server", cause);
        } finally {
            if (arguments.contains(Build.NAME) && removeBuildOptionsAfterBuild) {
                for (PropertyMapper mapper : PropertyMappers.getBuildTimeMappers()) {
                    removeProperty(mapper.getFrom().substring(3));
                }
            }
            if (!manualStop) {
                stop();
            }
        }
    }

    @Override
    public void stop() {
        if (isRunning()) {
            try {
                if (Environment.isWindows()) {
                    // On Windows, we're executing kc.bat in a runtime as "keycloak",
                    // so tha java process is an actual child process
                    // we have to kill first.
                    killChildProcessesOnWindows(false);
                }

                keycloak.destroy();
                keycloak.waitFor(10, TimeUnit.SECONDS);
                exitCode = keycloak.exitValue();

            } catch (Exception cause) {
                if (Environment.isWindows()) {
                    try {
                        killChildProcessesOnWindows(true);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to stop the server", e);
                    }
                }
                keycloak.destroyForcibly();
                throw new RuntimeException("Failed to stop the server", cause);
            }
        }

        shutdownOutputExecutor();
    }

    private void killChildProcessesOnWindows(boolean isForced) {
        for (ProcessHandle childProcessHandle : keycloak.children().collect(Collectors.toList())) {
            CompletableFuture<ProcessHandle> onExit = childProcessHandle.onExit();
            if (isForced) {
                childProcessHandle.destroyForcibly();
            } else {
                childProcessHandle.destroy();
            }
            //for whatever reason windows doesnt wait for the termination,
            // and parent process returns immediately with exitCode 1 but is not exited, leading to
            // "failed to start the distribution" bc files that should be deleted
            // are used by another process, so we need this here.
            onExit.join();
        }
    }

    @Override
    public List<String> getOutputStream() {
        return outputStream;
    }

    @Override
    public List<String> getErrorStream() {
        return errorStream;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public boolean isDebug() { return this.debug; }

    @Override
    public boolean isManualStop() { return this.manualStop; }

    @Override
    public String[] getCliArgs(List<String> arguments) {
        List<String> allArgs = new ArrayList<>();

        if (Environment.isWindows()) {
            allArgs.add(distPath.resolve("bin") + File.separator + SCRIPT_CMD_INVOKABLE);
        } else {
            allArgs.add(SCRIPT_CMD_INVOKABLE);
        }

        if (this.isDebug()) {
            allArgs.add("--debug");
        }

        if (!this.isManualStop()) {
            allArgs.add("-D" + LAUNCH_MODE + "=test");
        }

        this.relativePath = arguments.stream().filter(arg -> arg.startsWith("--http-relative-path")).map(arg -> arg.substring(arg.indexOf('=') + 1)).findAny().orElse("/");
        this.httpPort = Integer.parseInt(arguments.stream().filter(arg -> arg.startsWith("--http-port")).map(arg -> arg.substring(arg.indexOf('=') + 1)).findAny().orElse("8080"));

        allArgs.add("-Dkc.home.dir=" + distPath + File.separator);
        allArgs.addAll(arguments);

        return allArgs.toArray(String[]::new);
    }

    private void waitForReadiness() throws MalformedURLException {
        URL contextRoot = new URL("http://localhost:" + httpPort + ("/" + relativePath + "/realms/master/").replace("//", "/"));
        HttpURLConnection connection = null;
        long startTime = System.currentTimeMillis();

        while (true) {
            if (System.currentTimeMillis() - startTime > getStartTimeout()) {
                throw new IllegalStateException(
                        "Timeout [" + getStartTimeout() + "] while waiting for Quarkus server");
            }

            try {
                // wait before checking for opening a new connection
                Thread.sleep(1000);
                if ("https".equals(contextRoot.getProtocol())) {
                    HttpsURLConnection httpsConnection = (HttpsURLConnection) (connection = (HttpURLConnection) contextRoot.openConnection());
                    httpsConnection.setSSLSocketFactory(createInsecureSslSocketFactory());
                    httpsConnection.setHostnameVerifier(createInsecureHostnameVerifier());
                } else {
                    connection = (HttpURLConnection) contextRoot.openConnection();
                }

                connection.setReadTimeout((int) getStartTimeout());
                connection.setConnectTimeout((int) getStartTimeout());
                connection.connect();

                if (connection.getResponseCode() == 200) {
                    break;
                }
            } catch (Exception ignore) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private long getStartTimeout() {
        return TimeUnit.SECONDS.toMillis(120);
    }

    private HostnameVerifier createInsecureHostnameVerifier() {
        return new HostnameVerifier() {
            @Override
            public boolean verify(String s, SSLSession sslSession) {
                return true;
            }
        };
    }

    private SSLSocketFactory createInsecureSslSocketFactory() throws IOException {
        TrustManager[] trustAllCerts = new TrustManager[] {new X509TrustManager() {
            public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
            }

            public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        }};

        SSLContext sslContext;
        SSLSocketFactory socketFactory;

        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            socketFactory = sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException("Can't create unsecure trust manager");
        }
        return socketFactory;
    }

    private boolean isRunning() {
        return keycloak != null && keycloak.isAlive();
    }

    private void asyncReadOutput() {
        shutdownOutputExecutor();
        outputExecutor = Executors.newSingleThreadExecutor();
        outputExecutor.execute(this::readOutput);
    }

    private void shutdownOutputExecutor() {
        if (outputExecutor != null) {
            outputExecutor.shutdown();
            try {
                outputExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException cause) {
                throw new RuntimeException("Failed to terminate output executor", cause);
            } finally {
                outputExecutor = null;
            }
        }
    }

    private void reset() {
        outputStream.clear();
        errorStream.clear();
        exitCode = -1;
        keycloak = null;
        shutdownOutputExecutor();
    }

    private Path prepareDistribution() {
        try {
            Path distRootPath = Paths.get(System.getProperty("java.io.tmpdir")).resolve("kc-tests");
            distRootPath.toFile().mkdirs();

            File distFile = new File("../../dist/" + File.separator + "target" + File.separator + "keycloak-" + Version.VERSION_KEYCLOAK + ".zip");
            if (!distFile.exists()) {
                throw new RuntimeException("Distribution archive " + distFile.getAbsolutePath() +" doesn't exists");
            }
            distRootPath.toFile().mkdirs();
            String distDirName = distFile.getName();
            Path dPath = distRootPath.resolve(distDirName.substring(0, distDirName.lastIndexOf('.')));

            if (!inited || (reCreate || !dPath.toFile().exists())) {

                if (!Environment.isWindows()) {
                    FileUtils.deleteDirectory(dPath.toFile());
                } else {
                    deleteTempFilesOnWindows(dPath);
                }

                ZipUtils.unzip(distFile.toPath(), distRootPath);
            }

            // make sure script is executable
            if (!dPath.resolve("bin").resolve(SCRIPT_CMD).toFile().setExecutable(true)) {
                throw new RuntimeException("Cannot set " + SCRIPT_CMD + " executable");
            }

            inited = true;

            return dPath;
        } catch (Exception cause) {
            throw new RuntimeException("Failed to prepare distribution", cause);
        }
    }

    private void deleteTempFilesOnWindows(Path dPath) {
        if (Files.exists(dPath)) {
            try (Stream<Path> walk = Files.walk(dPath)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(s -> {
                            try {
                                Files.delete(s);
                            } catch (IOException e) {
                                throw new RuntimeException("Could not delete temp directory for distribution", e);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Could not traverse temp directory for distribution to delete files", e);
            }
        }
    }

    private void readOutput() {
        try (
                BufferedReader outStream = new BufferedReader(new InputStreamReader(keycloak.getInputStream()));
                BufferedReader errStream = new BufferedReader(new InputStreamReader(keycloak.getErrorStream()));
        ) {
            while (keycloak.isAlive()) {
                readStream(outStream, outputStream);
                readStream(errStream, errorStream);
            }
        } catch (Throwable cause) {
            throw new RuntimeException("Failed to read server output", cause);
        }
    }

    private void readStream(BufferedReader reader, List<String> stream) throws IOException {
        String line;

        while (reader.ready() && (line = reader.readLine()) != null) {
            stream.add(line);
            System.out.println(line);
        }
    }

    /**
     * The server is configured to redirect errors to output stream. This adds a limitation when checking whether a
     * message arrived via error stream.
     *
     * @param arguments the list of arguments to run the server
     * @throws Exception if something bad happens
     */
    private void startServer(List<String> arguments) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(getCliArgs(arguments));
        ProcessBuilder builder = pb.directory(distPath.resolve("bin").toFile());

        builder.environment().put("KEYCLOAK_ADMIN", "admin");
        builder.environment().put("KEYCLOAK_ADMIN_PASSWORD", "admin");

        keycloak = builder.start();
    }

    @Override
    public void setManualStop(boolean manualStop) {
        this.manualStop = manualStop;
    }

    @Override
    public void setProperty(String key, String value) {
        updateProperties(properties -> properties.put(key, value), distPath.resolve("conf").resolve("keycloak.conf").toFile());
    }

    @Override
    public void removeProperty(String key) {
        updateProperties(new Consumer<Properties>() {
            @Override
            public void accept(Properties properties) {
                properties.remove(key);
            }
        }, distPath.resolve("conf").resolve("keycloak.conf").toFile());
    }

    @Override
    public void setQuarkusProperty(String key, String value) {
        updateProperties(new Consumer<Properties>() {
            @Override
            public void accept(Properties properties) {
                properties.put(key, value);
            }
        }, getQuarkusPropertiesFile());
    }

    @Override
    public void deleteQuarkusProperties() {
        File file = getQuarkusPropertiesFile();

        if (file.exists()) {
            file.delete();
        }
    }

    @Override
    public void copyOrReplaceFileFromClasspath(String file, Path targetFile) {
        File targetDir = distPath.resolve(targetFile).toFile();

        targetDir.mkdirs();

        try {
            Files.copy(getClass().getResourceAsStream(file), targetDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException cause) {
            throw new RuntimeException("Failed to copy file", cause);
        }
    }

    private void updateProperties(Consumer<Properties> propertiesConsumer, File propertiesFile) {
        Properties properties = new Properties();

        if (propertiesFile.exists()) {
            try (
                FileInputStream in = new FileInputStream(propertiesFile);
            ) {

                properties.load(in);
            } catch (Exception e) {
                throw new RuntimeException("Failed to update " + propertiesFile, e);
            }
        }

        try (
            FileOutputStream out = new FileOutputStream(propertiesFile)
        ) {
            propertiesConsumer.accept(properties);
            properties.store(out, "");
        } catch (Exception e) {
            throw new RuntimeException("Failed to update " + propertiesFile, e);
        }
    }

    private File getQuarkusPropertiesFile() {
        return distPath.resolve("conf").resolve("quarkus.properties").toFile();
    }

    public Path getDistPath() {
        return distPath;
    }
}
