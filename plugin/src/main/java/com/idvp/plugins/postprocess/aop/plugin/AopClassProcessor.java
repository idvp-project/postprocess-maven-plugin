package com.idvp.plugins.postprocess.aop.plugin;

import com.idvp.plugins.postprocess.aop.api.VisibleForAop;
import javassist.*;
import org.apache.maven.plugin.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * @author Oleg Zinoviev
 * @since 26.02.18.
 */
class AopClassProcessor {
    private final Set<Path> classFiles;
    private final Logger logger = LoggerFactory.getLogger(AopClassProcessor.class);

    private final ClassPool classPool = ClassPool.getDefault();
    private final Set<CtClassInfo> loadedClasses = new HashSet<>();
    private final Set<CtClassInfo> filteredClasses = new HashSet<>();
    private final Map<CtClassInfo, VisibleForAop> inheritedClasses = new HashMap<>();

    AopClassProcessor(Set<Path> classFiles) {
        assert classFiles != null : "classFiles is null";
        assert logger != null : "logger is null";

        this.classFiles = classFiles;
    }

    //region loadClasses
    AopClassProcessor loadClasses() throws IOException {
        doLoadClasses();
        return this;
    }

    private void doLoadClasses() throws IOException {
        for (Path path : classFiles) {
            try (InputStream stream = Files.newInputStream(path, StandardOpenOption.READ)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Loading file: " + path);
                }

                CtClass ctClass = classPool.makeClass(stream);
                loadedClasses.add(new CtClassInfo(ctClass, path));

                if (logger.isDebugEnabled()) {
                    logger.debug("Loaded class \"" + ctClass.getName() + "\" from a file: " + path);
                }

            } catch (IOException | RuntimeException e) {
                logger.error("Error loading class: " + path, e);
                throw e;
            }
        }
    }
    //endregion

    //region filterClasses
    AopClassProcessor filterClasses() throws ClassNotFoundException {
        doFilterAnnotatedClasses();
        doFilterSubClasses();
        return this;
    }

    private void doFilterAnnotatedClasses() throws ClassNotFoundException {
        for (CtClassInfo info : loadedClasses) {
            if (info.ctClass.getAnnotation(VisibleForAop.class) != null) {
                filteredClasses.add(info);
            } else {
                for (CtMethod method : info.ctClass.getDeclaredMethods()) {
                    if (method.getAnnotation(VisibleForAop.class) != null) {
                        filteredClasses.add(info);
                        break;
                    }
                }
            }
        }
    }

    private void doFilterSubClasses() throws ClassNotFoundException {
        Set<CtClassInfo> currentFiltered = new HashSet<>(filteredClasses);
        for (CtClassInfo info : currentFiltered) {
            VisibleForAop annotation = (VisibleForAop) info.ctClass.getAnnotation(VisibleForAop.class);
            if (annotation != null && annotation.inherited()) {
                doFilterSubClasses(Collections.singleton(info), annotation);
            }
        }

    }

    private void doFilterSubClasses(Set<CtClassInfo> currentParentClasses, VisibleForAop parentAnnotation) {
        Set<CtClassInfo> subClasses = new HashSet<>();
        for (CtClassInfo info : currentParentClasses) {
            for (CtClassInfo loaded : loadedClasses) {
                try {
                    if (loaded.ctClass.getSuperclass() == info.ctClass) {
                        subClasses.add(loaded);
                        filteredClasses.add(loaded);
                        inheritedClasses.put(loaded, parentAnnotation);
                    }
                } catch (NotFoundException e) {
                    // Не нашли родительский класс в пуле. Игнорируем ошибку
                }
            }
        }

        if (!subClasses.isEmpty()) {
            doFilterSubClasses(subClasses, parentAnnotation);
        }
    }
    //endregion

    //region process
    void process() throws ClassNotFoundException, IOException, CannotCompileException {
        for (CtClassInfo info : filteredClasses) {
            if (logger.isDebugEnabled()) {
                logger.debug("Transforming class: " + info.ctClass.getName());
            }

            VisibleForAop annotation = (VisibleForAop) info.ctClass.getAnnotation(VisibleForAop.class);
            if (annotation == null) {
                annotation = inheritedClasses.get(info);
            }

            boolean processed = false;
            if (annotation != null) {
                processed = processClassLevelAnnotation(info.ctClass, annotation);
            } else {
                for (CtMethod method : info.ctClass.getDeclaredMethods()) {
                    if (Modifier.isPrivate(method.getModifiers())) {
                        //Пропускаем приватные методы
                        continue;
                    }

                    VisibleForAop methodAnnotation = (VisibleForAop) method.getAnnotation(VisibleForAop.class);
                    if (methodAnnotation == null) {
                        continue;
                    }

                    processed = processMethodLevelAnnotation(method, methodAnnotation) || processed;
                }
            }

            if (processed) {
                try (DataOutputStream stream = new DataOutputStream(Files.newOutputStream(info.path,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING))) {

                    info.ctClass.toBytecode(stream);
                    logger.info("Transformed class: " + info.ctClass.getName());
                } catch (IOException | CannotCompileException | RuntimeException e) {
                    logger.error("Error in transformation " + info.ctClass.getName(), e);
                    throw e;
                }
            }
        }
    }

    private boolean processClassLevelAnnotation(final CtClass ctClass,
                                                final VisibleForAop annotation) throws ClassNotFoundException {

        boolean processed = false;
        if (annotation.removeFinal()) {
            if (Modifier.isFinal(ctClass.getModifiers())) {
                ctClass.setModifiers(Modifier.clear(ctClass.getModifiers(), Modifier.FINAL));
                processed = true;
            }
        }

        for (CtMethod method : ctClass.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())) {
                //Пропускаем приватные методы
                continue;
            }

            VisibleForAop methodAnnotation = (VisibleForAop) method.getAnnotation(VisibleForAop.class);
            if (methodAnnotation == null) {
                methodAnnotation = annotation;
            }

            processed = processMethodLevelAnnotation(method, methodAnnotation) || processed;
        }

        return processed;
    }

    private boolean processMethodLevelAnnotation(CtMethod ctMethod, VisibleForAop annotation) {
        boolean processed = false;

        if (annotation.removeFinal()) {
            if (Modifier.isFinal(ctMethod.getModifiers())) {
                ctMethod.setModifiers(Modifier.clear(ctMethod.getModifiers(), Modifier.FINAL));
                processed = true;
            }
        }

        if (annotation.transformPackageToProtected()) {
            if (Modifier.isPackage(ctMethod.getModifiers())) {
                ctMethod.setModifiers(Modifier.setProtected(ctMethod.getModifiers()));
                processed = true;
            }
        }

        return processed;
    }
    //endregion


    private final static class CtClassInfo {
        private final CtClass ctClass;

        private final Path path;

        private CtClassInfo(CtClass ctClass,
                            Path path) {
            assert ctClass != null;
            assert path != null;

            this.ctClass = ctClass;
            this.path = path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CtClassInfo info = (CtClassInfo) o;
            return Objects.equals(ctClass, info.ctClass) &&
                    Objects.equals(path, info.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ctClass, path);
        }

        @Override
        public String toString() {
            return "CtClassInfo{" +
                    "ctClass=" + ctClass.getName() +
                    ", path=" + path +
                    '}';
        }
    }
}
