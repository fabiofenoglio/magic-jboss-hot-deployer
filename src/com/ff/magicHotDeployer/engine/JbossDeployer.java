package com.ff.magicHotDeployer.engine;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.commons.io.FileUtils;

import com.ff.magicHotDeployer.logging.Logger;

public class JbossDeployer {
	
	public static Boolean processEvent(
		Path eventFilePath, 
		WatchEvent.Kind<?> eventType, 
		Path baseSourcePath, 
		Path baseTargetFolder,
		String targetInnerPath
	) throws IOException {

		if (targetInnerPath != null && !"".equals(targetInnerPath)) {
			baseTargetFolder = Paths.get(baseTargetFolder.toAbsolutePath().toString(), targetInnerPath);
		}
		
		Path targetPath = reflectSourceToTargetPath(
			eventFilePath.toAbsolutePath().toString(), 
			baseSourcePath.toAbsolutePath().toString(), 
			baseTargetFolder.toAbsolutePath().toString()
		);

		Boolean isFolder = (
			targetPath.toFile().exists() ? 
					targetPath.toFile().isDirectory() :
					eventFilePath.toFile().isDirectory()
		);

		if (eventType == ENTRY_CREATE) {
			if (isFolder) {
				hotDeployNewFolder(eventFilePath.toFile(), targetPath.toFile());
			}
			else {
				// file created
				hotDeployFile(eventFilePath.toFile(), targetPath.toFile());
			}
		}
		else if (eventType == ENTRY_DELETE) {
			if (isFolder) {
				// folder deleted
				hotUndeployFolder(targetPath.toFile());
			}
			else {
				// file deleted
				hotUndeployFile(targetPath.toFile());
			}
		}
		else if (eventType == ENTRY_MODIFY) {
			if (isFolder) {
				Logger.trace("skipping folder modify event (nothing to do)");
				return true;
			}
			else {
				// file edited
				hotDeployFile(eventFilePath.toFile(), targetPath.toFile());
			}
		}
		else {
			Logger.warn("invalid event type " + eventType.toString());
			return false;
		}
		
		return true;
	}
	
	public static Boolean hotDeployNewFolder(File source, File target) throws IOException {
		// register directory and sub-directories

		final File sourceFinal = source;
		final File targetFinal = target;
		
        Files.walkFileTree(source.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
            	Logger.trace("new file in created folder to deploy : " + file.toAbsolutePath().toString());
            	
            	Path targetPath = reflectSourceToTargetPath(
            		file.toFile().getAbsolutePath(), 
            		sourceFinal.getAbsolutePath(), 
            		targetFinal.getAbsolutePath()
        		);
            	
            	hotDeployFile(file.toFile(), targetPath.toFile());
            	
            	return FileVisitResult.CONTINUE;
            }
        });
        
        return true;
	}
	
	public static Boolean hotDeployFile(File source, File target) throws IOException {
		Logger.trace("executing hotDeployFile from " + source.getAbsolutePath() + " to " + target.getAbsolutePath());
		if (!source.exists()) {
			Logger.trace("skipping (missing)");
			return false;
		}
		FileUtils.copyFile(source, target);
		return true;
	}

	public static Boolean hotUndeployFile(File target) throws IOException {
		if (!target.exists()) {
			Logger.trace("skipping (missing)");
			return false;
		}
		Logger.trace("executing hotUndeployFile from " + target.getAbsolutePath());
		target.delete();
		return true;
	}

	public static Boolean hotUndeployFolder(File target) throws IOException {
		if (!target.exists()) {
			Logger.trace("skipping (missing)");
			return false;
		}
		Logger.trace("executing hotUndeployFolder from " + target.getAbsolutePath());
		FileUtils.deleteDirectory(target);
		return true;
	}

	public static Path reflectSourceToTargetPath(String sourcePath, String sourceBase, String targetBase) {
		if (Logger.isEnabled(Logger.LEVEL_TRACE)) {
			Logger.trace("reflecting STT path from " + sourcePath);
			Logger.trace("based at " + sourceBase);
			Logger.trace("to " + targetBase);
		}
		
		String relative = new File(sourceBase).toURI().relativize(new File(sourcePath).toURI()).getPath();
		Path targetPath = Paths.get(targetBase, relative);
		
		if (Logger.isEnabled(Logger.LEVEL_TRACE)) {
			Logger.trace("reflected to " + targetPath.toAbsolutePath().toString());
		}
		
		return targetPath;
	}
	
	public static Path findDeploymentPath(String jbossHome, String matchPrefix) {
		
		Logger.trace("running deployment path serch from " + jbossHome + ", matching " + matchPrefix);
		
		Path path = Paths.get(jbossHome, "tmp/vfs/deployment");
		
		File file = new File(path.toAbsolutePath().toString());
		String[] directories = file.list(new FilenameFilter() {
			@Override
			public boolean accept(File current, String name) {
				return new File(current, name).isDirectory() && name.startsWith("deployment");
			}
		});
		
		if (directories.length < 1) {
			throw new RuntimeException("no base deployment directory found in " + path.toAbsolutePath().toString());
		}
		
		Logger.trace("found first deployment dir: " + path);
		
		file = path.resolve(directories[0]).toFile();
		directories = file.list(new FilenameFilter() {
			@Override
			public boolean accept(File current, String name) {
				return new File(current, name).isDirectory();
			}
		});
		
		Logger.trace("found " + directories.length + " exploded packages");
		
		String found = null;
		for (String d : directories) {
			if (d.startsWith(matchPrefix)) {
				found = d;
				break;
			}
		}
		
		if (found != null) {
			Logger.trace("found matching package " + found);
			return Paths.get(file.getPath(), found);
		}
		
		throw new RuntimeException("no deployed package found in " + file.getAbsolutePath());
	}
}
