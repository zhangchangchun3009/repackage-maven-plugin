package pers.zcc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
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

            preHandle(outputDir, finalName, tempDirPath);
            // get dependency lib path
            File lib = getNestedJarsDir(tempDir, useLibRelativePath);
            checkNestedJarsDir(lib);
            // get all jar file names in lib dir
            repackNestedJarsUncompressed(lib);

            afterHandle(finalName, tempDirPath, tempDir);
        } catch (Exception e) {
            throw new MojoExecutionException("Error repackaging jar file " + finalJar, e);
        }
    }

    private static void afterHandle(String finalName, String tempDirPath, File tempDir) throws Exception {
        File afterCmdfile = null;
        Process after = null;
        try {
            afterCmdfile = File.createTempFile("afterCmd", ".bat");
            BufferedWriter bw = new BufferedWriter(new FileWriter(afterCmdfile));
            bw.write("cd " + tempDir.getAbsolutePath());
            bw.newLine();
            // repackage all files of temp dir
            bw.write("jar -cf0M " + finalName + " *");
            bw.newLine();
            //copy new app jar
            bw.write("copy /Y " + finalName + " ..");
            bw.newLine();
            //cd ..
            bw.write("cd ..");
            bw.newLine();
            //remove temp dir
            bw.write("rmdir /S /Q " + tempDirPath);
            bw.newLine();
            bw.flush();
            bw.close();
            String cmd = "cmd /c " + afterCmdfile.getAbsolutePath();
            after = RUNTIME.exec(cmd, null, tempDir);
            after.waitFor();
        } catch (Exception e) {
            throw e;
        } finally {
            afterCmdfile.delete();
        }
    }

    private static void preHandle(File outputDir, String finalName, String tempDirPath) throws Exception {
        File cmdfile = null;
        Process pre = null;
        try {
            cmdfile = File.createTempFile("preCmd", ".bat");
            BufferedWriter bw = new BufferedWriter(new FileWriter(cmdfile));
            bw.write("cd " + outputDir.getAbsolutePath());
            bw.newLine();
            //copy app jar to temp dir
            bw.write("copy /Y " + finalName + " " + tempDirPath);
            bw.newLine();

            bw.write("cd " + tempDirPath);
            bw.newLine();
            // unpack app jar at temp dir
            bw.write("jar -xf " + finalName);
            bw.newLine();
            //delete app jar in temp dir
            bw.write("del /F /S /Q " + finalName);
            bw.newLine();
            bw.flush();
            bw.close();
            String cmd = "cmd /c " + cmdfile.getAbsolutePath();
            pre = RUNTIME.exec(cmd, null, outputDir);
            pre.waitFor();
        } catch (Exception e) {
            throw e;
        } finally {
            cmdfile.delete();
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
        int processors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(processors > 1 ? (processors - 1) : processors);
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
            repackDependency(jarFullName, jarName, lib, temp);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private static void repackDependency(String jarFullName, String jarName, File lib, File temp) throws Exception {
        File f = null;
        Process p = null;
        try {
            f = File.createTempFile("repackCmd", ".bat");
            BufferedWriter bw = new BufferedWriter(new FileWriter(f));
            bw.write("cd " + lib.getAbsolutePath());
            bw.newLine();
            bw.write("copy /Y " + jarFullName + " " + jarName);
            bw.newLine();

            bw.write("cd " + jarName);
            bw.newLine();
            // unpack app jar at temp dir
            bw.write("jar -xf " + jarFullName);
            bw.newLine();
            bw.write("del /F /S /Q " + jarFullName);
            bw.newLine();
            // repack app jar at temp dir
            bw.write("jar -cf0M " + jarFullName + " *");
            bw.newLine();
            bw.write("copy /Y " + jarFullName + " ..");
            bw.newLine();
            bw.write("cd ..");
            bw.newLine();
            //delete app jar in temp dir
            bw.write("rmdir /S /Q " + jarName);
            bw.newLine();
            bw.flush();
            bw.close();
            String cmd = "cmd /c " + f.getAbsolutePath();
            p = RUNTIME.exec(cmd, null, lib);
            p.waitFor();
        } catch (Exception e) {
            throw e;
        } finally {
            f.delete();
        }
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
