/*
 * Copyright 2023 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.test.plugin.junit5.launcher;

import com.navercorp.pinpoint.test.plugin.PluginClassLoading;
import com.navercorp.pinpoint.test.plugin.ReflectPluginTestVerifier;
import com.navercorp.pinpoint.test.plugin.TraceObjectManagable;
import com.navercorp.pinpoint.test.plugin.shared.ReflectionDependencyResolver;
import com.navercorp.pinpoint.test.plugin.shared.SharedPluginTestConstants;
import com.navercorp.pinpoint.test.plugin.shared.SharedTestBeforeAllInvoker;
import com.navercorp.pinpoint.test.plugin.shared.SharedTestExecutor;
import com.navercorp.pinpoint.test.plugin.shared.SharedTestLifeCycleWrapper;
import com.navercorp.pinpoint.test.plugin.shared.TestInfo;
import com.navercorp.pinpoint.test.plugin.shared.TestParameter;
import com.navercorp.pinpoint.test.plugin.shared.TestParameterParser;
import com.navercorp.pinpoint.test.plugin.shared.TestThreadFactory;
import com.navercorp.pinpoint.test.plugin.util.ArrayUtils;
import com.navercorp.pinpoint.test.plugin.util.ChildFirstClassLoader;
import com.navercorp.pinpoint.test.plugin.util.CollectionUtils;
import com.navercorp.pinpoint.test.plugin.util.ProfilerClass;
import com.navercorp.pinpoint.test.plugin.util.TestLogger;
import com.navercorp.pinpoint.test.plugin.util.ThreadContextCallable;
import com.navercorp.pinpoint.test.plugin.util.URLUtils;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.tinylog.TaggedLogger;

import java.io.File;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SharedPluginForkedTestLauncher {

    private static final TaggedLogger logger = TestLogger.getLogger();

    private static final String TEST_RESULT_SUCCESS = "SUCCESS";

    public static void main(String[] args) throws Exception {
        final String mavenDependencyResolverClassPaths = System.getProperty(SharedPluginTestConstants.MAVEN_DEPENDENCY_RESOLVER_CLASS_PATHS);
        if (mavenDependencyResolverClassPaths == null) {
            logger.error("mavenDependencyResolverClassPaths must not be empty");
            return;
        }
        final String sharedDependencyResolverClassPaths = System.getProperty(SharedPluginTestConstants.SHARED_DEPENDENCY_RESOLVER_CLASS_PATHS);
        final String sharedClazzName = System.getProperty(SharedPluginTestConstants.SHARED_CLASS_NAME);

        final String repositoryUrlString = System.getProperty(SharedPluginTestConstants.TEST_REPOSITORY_URLS);
        if (repositoryUrlString == null) {
            logger.error("repositoryUrls must not be empty");
            return;
        }
        logger.debug("-D{}={}", SharedPluginTestConstants.TEST_REPOSITORY_URLS, repositoryUrlString);

        final String testLocation = System.getProperty(SharedPluginTestConstants.TEST_LOCATION);
        if (testLocation == null) {
            logger.error("testLocation must not be empty");
            return;
        }
        logger.debug("-D{}={}", SharedPluginTestConstants.TEST_LOCATION, testLocation);

        final String testClazzName = System.getProperty(SharedPluginTestConstants.TEST_CLAZZ_NAME);
        if (testClazzName == null) {
            logger.error("testClazzName must not be empty");
            return;
        }
        logger.debug("-D{}={}", SharedPluginTestConstants.TEST_CLAZZ_NAME, testClazzName);

        String loggerEnable = System.getProperty(SharedPluginTestConstants.TEST_LOGGER);
        if (loggerEnable == null) {
            logger.debug("-D{} is not set", SharedPluginTestConstants.TEST_LOGGER);
            loggerEnable = Boolean.TRUE.toString();
        }
        final boolean testLogger = Boolean.parseBoolean(loggerEnable);
        logger.debug("-D{}={}", SharedPluginTestConstants.TEST_LOGGER, testLogger);

        if (ArrayUtils.isEmpty(args)) {
            logger.error("test must not be empty");
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("main args:{}", Arrays.toString(args));
        }

        String[] mavenDependencyResolverClassPathArray = mavenDependencyResolverClassPaths.split(File.pathSeparator);
        String[] sharedDependencyResolverClassPathArray = null;
        if (sharedDependencyResolverClassPaths != null) {
            sharedDependencyResolverClassPathArray = sharedDependencyResolverClassPaths.split(File.pathSeparator);
        }

        String[] repositoryUrls = repositoryUrlString.split(",");
        TestParameterParser parser = new TestParameterParser();
        List<TestParameter> testParameters = parser.parse(args);
        SharedPluginForkedTestLauncher pluginTest = new SharedPluginForkedTestLauncher(testClazzName, testLocation, testLogger,
                mavenDependencyResolverClassPathArray, sharedClazzName, sharedDependencyResolverClassPathArray, repositoryUrls, testParameters, System.out);
        pluginTest.execute();
    }

    private final String testClazzName;
    private final String testLocation;
    private final boolean testLogger;
    private final String[] mavenDependencyResolverClassPaths;
    private final String sharedClazzName;
    private final String[] sharedDependencyResolverClassPaths;
    private final String[] repositoryUrls;
    private final List<TestParameter> testParameters;
    private final PrintStream out;

    public SharedPluginForkedTestLauncher(String testClazzName, String testLocation, boolean testLogger,
                                          String[] mavenDependencyResolverClassPaths,
                                          String sharedClassName,
                                          String[] sharedDependencyResolverClassPaths,
                                          String[] repositoryUrls,
                                          List<TestParameter> testParameters, PrintStream out) {
        this.testClazzName = testClazzName;
        this.testLocation = testLocation;
        this.testLogger = testLogger;
        this.mavenDependencyResolverClassPaths = mavenDependencyResolverClassPaths;
        this.sharedClazzName = sharedClassName;
        this.sharedDependencyResolverClassPaths = sharedDependencyResolverClassPaths;
        this.repositoryUrls = repositoryUrls;
        this.testParameters = testParameters;
        this.out = out;
    }

    private List<TestInfo> newTestCaseInfo(List<TestParameter> testParameters, Path testClazzLocation, String[] repositoryUrls, ClassLoader dependencyClassLoader) throws Exception {
        ReflectionDependencyResolver dependencyResolver = new ReflectionDependencyResolver(dependencyClassLoader, repositoryUrls);
        List<Path> loggerDependencies = getLoggerDependencies(dependencyResolver, dependencyClassLoader);
        logger.debug("loggerDependency:{}", loggerDependencies);

        List<TestInfo> testInfos = new ArrayList<>();
        for (TestParameter testParameter : testParameters) {
            final List<Path> testDependency = new ArrayList<>();
            testDependency.add(testClazzLocation);

            testDependency.addAll(loggerDependencies);

            List<Path> testParameterDependency = getTestParameterDependency(dependencyClassLoader, dependencyResolver, testParameter);
            testDependency.addAll(testParameterDependency);

            final TestInfo testInfo = new TestInfo(testParameter.getTestId(), testDependency, Arrays.asList(repositoryUrls));
            testInfos.add(testInfo);
        }
        return testInfos;
    }

    private List<Path> getTestParameterDependency(ClassLoader mavenDependencyResolverClassLoader,
                                                  ReflectionDependencyResolver dependencyResolver,
                                                  TestParameter testParameter) throws Exception {

        final List<String> mavenDependencies = testParameter.getMavenDependencies();
        List<Path> testDependencyFileList = lookup(dependencyResolver, mavenDependencies, mavenDependencyResolverClassLoader);
        if (logger.isDebugEnabled()) {
            logger.debug("@Dependency {}", mavenDependencies);
            for (Path file : testDependencyFileList) {
                logger.debug("-> {}", file);
            }
        }
        return testDependencyFileList;
    }

    private List<Path> getLoggerDependencies(ReflectionDependencyResolver dependencyResolver, ClassLoader mavenDependencyResolverClassLoader) throws Exception {
        if (!testLogger) {
            return Collections.emptyList();
        }
        List<String> dependencyLib = PluginClassLoading.LOGGER_DEPENDENCY;
        List<Path> libFiles = lookup(dependencyResolver, dependencyLib, mavenDependencyResolverClassLoader);
        if (logger.isDebugEnabled()) {
            logger.debug("LoggerDependency {}", dependencyLib);
            for (Path libFile : libFiles) {
                logger.debug("-> {}", libFile);
            }
        }
        return libFiles;
    }

    private List<Path> lookup(final ReflectionDependencyResolver dependencyResolver, final List<String> dependencyLib, ClassLoader cl) throws Exception {
        Callable<List<Path>> callable = new ThreadContextCallable<>(new Callable<List<Path>>() {
            @Override
            public List<Path> call() throws Exception {
                return dependencyResolver.lookup(dependencyLib);
            }
        }, cl);
        return callable.call();
    }

    private void logTestInformation() {
        logger.info("[{}] {}", getClass().getSimpleName(), testClazzName);

        if (logger.isDebugEnabled()) {
            for (String mavenDependencyResolverClassPath : mavenDependencyResolverClassPaths) {
                logger.debug("{}: {}", SharedPluginTestConstants.MAVEN_DEPENDENCY_RESOLVER_CLASS_PATHS, mavenDependencyResolverClassPath);
            }
            for (TestParameter testParameter : testParameters) {
                logger.debug("{} {}", testClazzName, testParameter);
            }
            for (String repositoryUrl : repositoryUrls) {
                logger.debug("{}: {}", SharedPluginTestConstants.TEST_REPOSITORY_URLS, repositoryUrl);
            }
        }
    }

    public void execute() throws Exception {
        logTestInformation();
        URL[] classPath = URLUtils.fileToUrls(mavenDependencyResolverClassPaths);
        ClassLoader mavenDependencyResolverClassLoader = new ChildFirstClassLoader(classPath);
        Path testClazzLocation = Paths.get(testLocation);
        List<TestInfo> testInfos = newTestCaseInfo(testParameters, testClazzLocation, repositoryUrls, mavenDependencyResolverClassLoader);

        executes(testInfos);
    }

    private void executes(List<TestInfo> testInfos) {
        if (!CollectionUtils.hasLength(testInfos)) {
            return;
        }

        SharedTestExecutor sharedTestExecutor = null;
        SharedTestLifeCycleWrapper sharedTestLifeCycleWrapper = null;
        if (sharedClazzName != null && sharedDependencyResolverClassPaths != null) {
            URL[] classPath = URLUtils.fileToUrls(sharedDependencyResolverClassPaths);
            final ClassLoader sharedClassLoader = new ChildFirstClassLoader(classPath);
            sharedTestExecutor = new SharedTestExecutor(sharedClazzName, sharedClassLoader);
            sharedTestExecutor.startBefore(10, TimeUnit.MINUTES);
            sharedTestLifeCycleWrapper = sharedTestExecutor.getSharedClassWrapper();
        }

        for (TestInfo testInfo : testInfos) {
            execute(testInfo, sharedTestLifeCycleWrapper);
        }

        if (sharedTestLifeCycleWrapper != null) {
            sharedTestExecutor.startAfter(5, TimeUnit.MINUTES);
        }
    }

    private ClassLoader createTestClassLoader(TestInfo testInfo) {
        List<Path> dependencyFileList = testInfo.getDependencyFileList();
        if (logger.isDebugEnabled()) {
            for (Path dependency : dependencyFileList) {
                logger.debug("testcase cl lib :{}", dependency);
            }
        }
        URL[] urls = URLUtils.pathToUrls(dependencyFileList);
        return new ChildFirstClassLoader(urls, ProfilerClass.PINPOINT_PROFILER_CLASS);
    }

    private void execute(final TestInfo testInfo, SharedTestLifeCycleWrapper sharedTestLifeCycleWrapper) {
        final ClassLoader testClassLoader = createTestClassLoader(testInfo);

        Callable<String> callable = new Callable<String>() {
            @Override
            public String call() {
                final Class<?> testClazz = loadClass();
                boolean manageTraceObject = !testClazz.isAnnotationPresent(TraceObjectManagable.class);
                SharedTestBeforeAllInvoker invoker = new SharedTestBeforeAllInvoker(testClazz);
                try {
                    if (sharedTestLifeCycleWrapper != null) {
                        invoker.invoke(sharedTestLifeCycleWrapper.getLifeCycleResult());
                    }
                } catch (Throwable th) {
                    logger.error(th, "invoker setter method failed. testClazz:{} testId:{}", testClazzName, testInfo.getTestId());
                    return th.getMessage();
                }

                LauncherConfig launcherConfig = getLauncherConfig(testInfo.getTestId(), manageTraceObject);

                LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectClass(testClazz))
                        .build();
                try (LauncherSession session = LauncherFactory.openSession(launcherConfig)) {
                    Launcher launcher = session.getLauncher();
                    SharedPluginForkedTestLauncherListener listener = new SharedPluginForkedTestLauncherListener(testInfo.getTestId());
                    launcher.execute(request, new TestExecutionListener() {
                        @Override
                        public void executionStarted(TestIdentifier testIdentifier) {
                            listener.executionStarted(testIdentifier);
                        }

                        @Override
                        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
                            listener.executionFinished(testIdentifier, testExecutionResult);
                        }
                    });
                }
                return TEST_RESULT_SUCCESS;
            }

            private Class<?> loadClass() {
                try {
                    return testClassLoader.loadClass(testClazzName);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        ExecutorService executorService = newExecutorService(testInfo, testClassLoader);
        Future<String> submit = executorService.submit(callable);
        try {
            String result = submit.get(5, TimeUnit.MINUTES);
            if (!TEST_RESULT_SUCCESS.equals(result)) {
                logger.error("test failed. testClazz:{} testId:{} result:{}", testClazzName, testInfo.getTestId(), result);
            }
        } catch (TimeoutException ex) {
            submit.cancel(true);
            ex.printStackTrace();
        } catch (Throwable ex) {
            ex.printStackTrace();
        } finally {
            ReflectPluginTestVerifier.getInstance().cleanUp(true);
            executorService.shutdown();
        }
    }

    private ExecutorService newExecutorService(TestInfo testInfo, ClassLoader testClassLoader) {
        String threadName = testClazzName + " " + testInfo.getTestId() + " Thread";
        ThreadFactory testThreadFactory = new TestThreadFactory(threadName, testClassLoader);
        return Executors.newSingleThreadExecutor(testThreadFactory);
    }

    private LauncherConfig getLauncherConfig(String testId, boolean manageTraceObject) {
        return LauncherConfig.builder()
                .enableTestEngineAutoRegistration(false)
                .enableLauncherSessionListenerAutoRegistration(false)
                .enableLauncherDiscoveryListenerAutoRegistration(false)
                .enablePostDiscoveryFilterAutoRegistration(false)
                .enableTestExecutionListenerAutoRegistration(false)
                .addTestEngines(new JupiterTestEngine())
                .addTestExecutionListeners(new SharedPluginForkedTestExecutionListener(testId))
                .addTestExecutionListeners(new SharedPluginForkedTestVerifierExecutionListener(manageTraceObject))
                .build();
    }

}
