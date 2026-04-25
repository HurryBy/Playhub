package com.tvbox.web.service;

import com.tvbox.web.TvboxWebApplication;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class AppPathsService {

    private final Path baseDir;
    private final List<Path> searchRoots;

    public AppPathsService() {
        this.searchRoots = discoverRoots();
        this.baseDir = chooseBaseDir(searchRoots);
        System.setProperty("tvbox.app.base", this.baseDir.toString());
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public Path resolveFromBase(String relativePath) {
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDir.resolve(path).normalize();
    }

    public Path resolveExisting(String relativePath) {
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        for (Path root : searchRoots) {
            Path candidate = root.resolve(path).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return resolveFromBase(relativePath);
    }

    private List<Path> discoverRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        Path workingDir = Path.of("").toAbsolutePath().normalize();
        addCandidate(roots, workingDir);
        addCandidate(roots, workingDir.resolve("tvbox-webapp"));

        Path applicationDir = applicationDir();
        addCandidate(roots, applicationDir);
        addCandidate(roots, parentOf(applicationDir));
        addCandidate(roots, parentOf(parentOf(applicationDir)));

        return new ArrayList<>(roots);
    }

    private Path chooseBaseDir(List<Path> roots) {
        Path fallback = Path.of("").toAbsolutePath().normalize();
        Path best = fallback;
        int bestScore = score(fallback);
        for (Path root : roots) {
            int score = score(root);
            if (score > bestScore) {
                best = root;
                bestScore = score;
            }
        }
        return best;
    }

    private int score(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return -1;
        }
        int score = 0;
        if (Files.isDirectory(root.resolve("scripts"))) {
            score += 8;
        }
        if (Files.exists(root.resolve("pom.xml"))) {
            score += 4;
        }
        if (Files.isDirectory(root.resolve("config"))) {
            score += 4;
        }
        if (Files.isDirectory(root.resolve("tools"))) {
            score += 2;
        }
        if (Files.isDirectory(root.resolve("app"))) {
            score += 2;
        }
        if (Files.isDirectory(root.resolve("src"))) {
            score += 2;
        }
        return score;
    }

    private Path applicationDir() {
        try {
            File dir = new ApplicationHome(TvboxWebApplication.class).getDir();
            if (dir != null) {
                return dir.toPath().toAbsolutePath().normalize();
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private Path parentOf(Path path) {
        return path == null ? null : path.getParent();
    }

    private void addCandidate(LinkedHashSet<Path> roots, Path path) {
        if (path != null) {
            roots.add(path.normalize());
        }
    }
}
