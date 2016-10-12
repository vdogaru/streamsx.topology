package com.ibm.streamsx.topology.internal.context.remote;

import static com.ibm.streamsx.topology.internal.gson.GsonUtilities.jstring;
import static com.ibm.streamsx.topology.internal.gson.GsonUtilities.object;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonObject;
import com.ibm.streamsx.topology.internal.process.CompletedFuture;

public class ZippedToolkitRemoteContext extends ToolkitRemoteContext {
    @Override
    public Type getType() {
        return Type.ZIPPED_TOOLKIT;
    }
    
    @Override
    public Future<File> submit(JsonObject submission) throws Exception {
        File toolkitRoot = super.submit(submission).get();
        return createCodeArchive(toolkitRoot, submission);        
    }
    
    public static Future<File> createCodeArchive(File toolkitRoot, JsonObject submission) throws IOException, URISyntaxException {
        
        JsonObject jsonGraph = object(submission, SUBMISSION_GRAPH);
        String namespace = jstring(jsonGraph, "namespace");
        String name = jstring(jsonGraph, "name");
        String tkName = toolkitRoot.getName();
        
        Path zipOutPath = pack(toolkitRoot.toPath(), namespace, name, tkName);
        
        JsonObject deployInfo = object(submission, SUBMISSION_DEPLOY);
        deleteToolkit(toolkitRoot, deployInfo);
        
        return new CompletedFuture<File>(zipOutPath.toFile());
    }
        
    private static Path pack(final Path folder, String namespace, String name, String tkName) throws IOException, URISyntaxException {
        Path zipFilePath = Paths.get(folder.toAbsolutePath().toString() + ".zip");
        String workingDir = zipFilePath.getParent().toString();
        // com.ibm.streamsx.topology/lib/com.ibm.streamsx.topology.jar
        File jarLocation = new File(ZippedToolkitRemoteContext.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
        // com.ibm.streamsx.topology
        File topologyToolkit = jarLocation.getParentFile().getParentFile();    
        
        // tkManifest is the list of toolkits contained in the archive
        try (PrintWriter tkManifest = new PrintWriter("manifest_tk.txt", "UTF-8")) {
            tkManifest.println(tkName);
            tkManifest.println("com.ibm.streamsx.topology");
        }
        
        // mainComposite is a string of the namespace and the main composite.
        // This is used by the Makefile
        try (PrintWriter mainComposite = new PrintWriter("main_composite.txt", "UTF-8")) {
            mainComposite.print(namespace + "::" + name);
        }
        
        List<Path> paths = new ArrayList<Path>();
        paths.add(Paths.get(topologyToolkit.getAbsolutePath()));
        paths.add(Paths.get(workingDir + "/manifest_tk.txt"));
        paths.add(Paths.get(workingDir + "/main_composite.txt"));
        paths.add(Paths.get(topologyToolkit.getAbsolutePath() + "/opt/python/templates/common/Makefile"));
        paths.add(folder);
        
        addAllToZippedArchive(paths, zipFilePath);  
        paths.get(1).toFile().delete();
        paths.get(2).toFile().delete();
        
        return zipFilePath;
    }
    
    
    private static void addAllToZippedArchive(List<Path> starts, Path zipFilePath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFilePath.toFile());
                ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (Path start : starts) {
                String startName = start.getFileName().toString();
                Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String entryName = startName;
                        String relativePath = start.relativize(file).toString();
                        if(relativePath.length() != 0){
                            entryName = entryName + "/" + relativePath;
                        }
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }

                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        zos.putNextEntry(new ZipEntry(startName + "/" + start.relativize(dir).toString() + "/"));
                        zos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }
}