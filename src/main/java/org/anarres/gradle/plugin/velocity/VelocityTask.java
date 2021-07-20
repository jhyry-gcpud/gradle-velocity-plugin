package org.anarres.gradle.plugin.velocity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.log.SystemLogChute;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.gradle.api.GradleException;
import org.gradle.api.file.EmptyFileVisitor;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskAction;


/**
 * A bare velocity task.
 *
 * You may use this to do arbitrary velocity processing without
 * necessarily applying the plugin.
 *
 * @author shevek
 */
public class VelocityTask extends SourceTask {
   private static interface Collector {
      public void accept(@Nonnull File dir);
   }

   private static class IncludePathCollector implements Collector {
      private final StringBuilder out = new StringBuilder();


      @Override
      public void accept(final File dir) {
         if (out.length() > 0) {
            out.append(", ");
         }
         out.append(dir.getAbsolutePath());
      }
   }

   private class IncludeFileCollector implements Collector {
      private FileCollection out = getProject().files();


      @Override
      public void accept(final File dir) {
         out = out.plus(getProject().fileTree(dir));
      }
   }


   private File outputDir;

   private List<File> includeDirs = new ArrayList<>();

   private Map<String, Object> contextValues = new HashMap<>();


   @OutputDirectory
   @Nonnull // Not @Optional
   public File getOutputDir() {
      return outputDir;
   }


   public void setOutputDir(@Nonnull final File outputDir) {
      this.outputDir = outputDir;
   }


   @Input
   @Optional
   @CheckForNull
   public List<File> getIncludeDirs() {
      return includeDirs;
   }


   public void setIncludeDirs(@Nonnull final List<? extends File> includeDirs) {
      this.includeDirs = new ArrayList<>(includeDirs);
   }


   @Input
   @Optional
   @CheckForNull
   public Map<String, Object> getContextValues() {
      return contextValues;
   }


   public void setContextValues(@Nonnull final Map<? extends String, ? extends Object> contextValues) {
      this.contextValues = new HashMap<>(contextValues);
   }


   public void contextValue(@Nonnull final String key, @CheckForNull final Object value) {
      if (value == null) {
         contextValues.remove(key);
      }
      else {
         contextValues.put(key, value);
      }
   }


   private void setProperty(final VelocityEngine engine, final String name, final Object value) {
      getLogger().info("VelocityEngine property: " + name + " = " + value);
      engine.setProperty(name, value);
   }


   private void collectDir(@Nonnull final Collector collector, @Nonnull final File dir) {
      getLogger().info("Collecting dir " + dir);
      collector.accept(dir);
   }


   private void collectDirs(@Nonnull final Collector collector, @CheckForNull final Iterable<File> dirs) {
      if (dirs != null) {
         for (File dir : dirs) {
            collectDir(collector, dir);
         }
      }
   }


   private void collectUnknown(@Nonnull final Collector collector, @Nonnull final FileTree source) {
      getLogger().info("Attempting to collect " + source.getClass() + ":" + source);
      for (FileSystemLocation location : source.getElements().getOrElse(Collections.<FileSystemLocation> emptySet())) {
         getLogger().info("Attempting to add " + location.getClass() + ":" + location);
         collector.accept(location.getAsFile());
      }
   }


   @InputFiles
   @PathSensitive(PathSensitivity.RELATIVE)
   @Nonnull
   // Not @Optional
   /* pp */ FileCollection getIncludeFiles() {
      getSource();
      IncludeFileCollector collector = new IncludeFileCollector();
      collectUnknown(collector, getSource());
      collectDirs(collector, getIncludeDirs());
      for (File file : collector.out) {
         getLogger().info("Including " + file);
      }
      return collector.out;
   }


   @TaskAction
   public void runVelocity() throws Exception {
      final FileTree inputFiles = getSource();
      final File outputDir = getOutputDir();

      Files.walkFileTree(outputDir.toPath(), new SimpleFileVisitor<Path>() {
         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
             Files.delete(file);
             return FileVisitResult.CONTINUE;
         }
         @Override
         public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
             Files.delete(dir);
             return FileVisitResult.CONTINUE;
         }
      });

      outputDir.mkdirs();


      inputFiles.visit(new EmptyFileVisitor() {
         @Override
         public void visitFile(final FileVisitDetails fvd) {
            try {
               File outputFile = fvd.getRelativePath().getFile(outputDir);
               if (getLogger().isDebugEnabled()) {
                  getLogger().debug("Preprocessing " + fvd.getFile() + " -> " + outputFile);
               }
               VelocityContext context = new VelocityContext();
               Map<String, Object> contextValues = getContextValues();
               if (contextValues != null) {
                  getLogger().info("Applying context to VelocityContext for evaluation: " + contextValues);
                  for (Map.Entry<String, Object> e : contextValues.entrySet()) {
                     context.put(e.getKey(), e.getValue());
                  }
               }
               else {
                  getLogger().warn("Velocity cannot be populated with null context!");
               }
               context.put("project", getProject());
               context.put("package", DefaultGroovyMethods.join(fvd.getRelativePath().getParent().getSegments(), "."));
               context.put("class", fvd.getRelativePath().getLastName().replaceFirst("\\.java$", ""));
               outputFile.getParentFile().mkdirs();

               try (InputStream in = new FileInputStream(fvd.getFile());
                        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                        OutputStream out = new FileOutputStream(outputFile);
                        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                  final VelocityEngine engine = createEngine();
                  engine.evaluate(context, writer, fvd.getRelativePath().toString(), reader);
               }
            }
            catch (NullPointerException | IOException e) {
               throw new GradleException("Failed to process " + fvd, e);
            }
         }
      });
   }


   private VelocityEngine createEngine() {
      final VelocityEngine engine = new VelocityEngine();
      setProperty(engine, VelocityEngine.RUNTIME_LOG_LOGSYSTEM_CLASS, SystemLogChute.class.getName());
      setProperty(engine, VelocityEngine.RESOURCE_LOADER, "file");
      setProperty(engine, VelocityEngine.FILE_RESOURCE_LOADER_CACHE, "true");
      // FILE_RESOURCE_LOADER_PATH actually takes a comma separated list.
      IncludePathCollector collector = new IncludePathCollector();
      collectUnknown(collector, getSource());
      collectDirs(collector, getIncludeDirs());
      setProperty(engine, VelocityEngine.FILE_RESOURCE_LOADER_PATH, collector.out.toString());
      return engine;
   }
}
