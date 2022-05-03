package pers.zcc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * @author zhangchangchun
 * @Date 2022年4月29日
 */
@Mojo(name = "repackjar", defaultPhase = LifecyclePhase.PACKAGE)
public class JarMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true, readonly = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.basedir}", property = "basedir", required = true, readonly = true)
    private File basedir;

    @Parameter(defaultValue = "${project.build.sourceDirectory}", property = "sourceDirectory", required = true, readonly = true)
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.resources}", property = "resources", required = true, readonly = true)
    private List<Resource> resources;

    @Parameter(defaultValue = "${project.build.testResources}", property = "testResources", required = true, readonly = true)
    private List<Resource> testResources;

    @Parameter(defaultValue = "${project.artifactId}", property = "artifactId", required = true, readonly = true)
    private String artifactId;

    @Parameter(defaultValue = "${jarFileSuffix}", property = "jarFileSuffix", required = false, readonly = false)
    private String jarFileSuffix;

    @Parameter(defaultValue = "${includes}", property = "includes", required = false, readonly = false)
    private String[] includes;

    @Parameter(defaultValue = "${libRelativePath}", property = "libRelativePath", required = false, readonly = false)
    private String libRelativePath;

    static final String DEFAULT_LIB_RELATIVE_PATH = "/lib";

    static final Runtime RUNTIME = Runtime.getRuntime();

    static final String TEMP_DIR_PATH = "repackjarTemp";

    @Override
    public void execute() throws MojoExecutionException {
        String finalName = artifactId + (jarFileSuffix == null ? "" : jarFileSuffix) + ".jar";
        String useLibRelativePath = (libRelativePath == null ? DEFAULT_LIB_RELATIVE_PATH : libRelativePath);
        dodo(outputDirectory, finalName, TEMP_DIR_PATH, useLibRelativePath);
    }

    static void dodo(File outputDir, String finalName, String tempDirPath, String useLibRelativePath)
            throws MojoExecutionException {
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        //check if app jar exists
        File finalJar = new File(outputDir, finalName);
        if (!finalJar.exists()) {
            throw new MojoExecutionException("jar file -" + finalName + " - hasn't been build ");
        }
        try {
            // make temp dir
            File tempDir = makeWorkTempDir(outputDir, tempDirPath);
            //copy app jar to temp dir
            copy(finalName, tempDirPath, outputDir);
            // unpack app jar at temp dir
            unpackJar(finalName, tempDir);
            //delete app jar in temp dir
            delete(finalName, tempDir);
            // get dependency lib path
            File lib = getNestedJarsDir(tempDir, useLibRelativePath);
            checkNestedJarsDir(lib);
            // get all jar file names in lib dir
            repackNestedJarsUncompressed(lib);
            // repackage all files of temp dir
            repackJarUncompressed(finalName, "*", tempDir);
            //copy new app jar
            copy(finalName, "..", tempDir);
            //remove temp dir
            removeDir(tempDirPath, outputDir);
        } catch (Exception e) {
            throw new MojoExecutionException("Error repackaging jar file " + finalJar, e);
        }
    }

    private static File getNestedJarsDir(File tempDir, String useLibRelativePath) {
        File lib = new File(tempDir, useLibRelativePath);
        return lib;
    }

    private static void repackNestedJarsUncompressed(File lib)
            throws IOException, InterruptedException, ExecutionException, MojoExecutionException {
        String cmd1 = "cmd /c dir /b *.jar";
        Process p1 = RUNTIME.exec(cmd1, null, lib);
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try (InputStream in1 = p1.getInputStream();
                BufferedReader br1 = new BufferedReader(new InputStreamReader(in1));) {
            List<CompletableFuture<Boolean>> cfList = new ArrayList<CompletableFuture<Boolean>>();
            String line;
            while ((line = br1.readLine()) != null) {
                final String jarName = line;
                // unpack dependency and repackage it uncompressed
                CompletableFuture<Boolean> cf = CompletableFuture.supplyAsync(() -> {
                    return repackDependency(lib, jarName);
                }, executor);
                cfList.add(cf);
            }
            CompletableFuture.allOf(cfList.toArray(new CompletableFuture[] {})).join();
            boolean nothingWrong = true;
            for (CompletableFuture<Boolean> cf : cfList) {
                nothingWrong = nothingWrong && cf.get();
                if (!nothingWrong) {
                    break;
                }
            }
            if (!nothingWrong) {
                throw new MojoExecutionException("Error repackaging nested dependency jar file, some job failed ");
            }
        } finally {
            p1.destroy();
            executor.shutdown();
        }
    }

    private static void checkNestedJarsDir(File lib) throws MojoExecutionException {
        if (!lib.exists() || !lib.isDirectory()) {
            throw new MojoExecutionException(
                    "relative path of dependency jar library directory --" + lib.getPath() + "-- does not exit");
        }
    }

    private static File makeWorkTempDir(File outputDir, String tempDirPath) {
        File tempDir = new File(outputDir, tempDirPath);
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        return tempDir;
    }

    private static boolean repackDependency(File lib, String jarFullName) {
        String nestedJarName = jarFullName;
        String jarName = nestedJarName.substring(0, nestedJarName.lastIndexOf("."));
        File temp = new File(lib, jarName);
        try {
            temp.mkdir();

            copy(jarFullName, jarName, lib);

            unpackJar(jarFullName, temp);

            delete(jarFullName, temp);

            repackJarUncompressed(jarFullName, "*", temp);

            copy(jarFullName, "..", temp);

            removeDir(jarName, lib);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private static void delete(String fileName, File cmdExeDir) throws IOException, InterruptedException {
        String cmd = "cmd /c del /F /S /Q " + fileName;
        syncExec(cmd, cmdExeDir);
    }

    private static void copy(String fileName, String toDir, File cmdExeDir) throws IOException, InterruptedException {
        String cmd = "cmd /c copy /Y " + fileName + " " + toDir;
        syncExec(cmd, cmdExeDir);
    }

    private static void removeDir(String dir, File cmdExeDir) throws IOException, InterruptedException {
        String cmd = "cmd /c rmdir /S /Q " + dir;
        syncExec(cmd, cmdExeDir);
    }

    private static void unpackJar(String jarName, File cmdExeDir) throws IOException, InterruptedException {
        String cmd = "cmd /c jar -xf " + jarName;
        syncExec(cmd, cmdExeDir);
    }

    private static void repackJarUncompressed(String jarName, String dirsToPackWith, File cmdExeDir)
            throws IOException, InterruptedException {
        String cmd = "cmd /c jar -cf0M " + jarName + " " + dirsToPackWith;
        syncExec(cmd, cmdExeDir);
    }

    private static void syncExec(String cmd, File cmdExeDir) throws IOException, InterruptedException {
        Process p = RUNTIME.exec(cmd, null, cmdExeDir);
        p.waitFor();
    }

//    public static void main(String[] args) {
//        String outputDirPath = "D:\\programming\\github\\scm\\scm.web\\target";
//        String finalName = "scm.web-assembly.jar";
//        try {
//            dodo(new File(outputDirPath), finalName, TEMP_DIR_PATH, DEFAULT_LIB_RELATIVE_PATH);
//        } catch (MojoExecutionException e) {
//            e.printStackTrace();
//        }
//    }
}
