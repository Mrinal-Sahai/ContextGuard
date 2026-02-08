package io.contextguard.analysis.flow;


import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Manages Git repository cloning and branch checkout.
 *
 * LIFECYCLE:
 * 1. Clone repository to temp directory
 * 2. Checkout base branch (extract AST)
 * 3. Checkout head branch (extract AST)
 * 4. Cleanup temp directory
 */
@Service
public class GitRepositoryService {

    private static final String TEMP_DIR_PREFIX = "pr-analysis-";

    /**
     * Clone repository and return workspace directory.
     *
     * @param repoUrl GitHub clone URL (https)
     * @param authToken GitHub personal access token (optional for public repos)
     * @return Path to cloned repository
     */
    public Path cloneRepository(String repoUrl, String authToken) throws GitAPIException, IOException {

        Path tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX);

        try {
            Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tempDir.toFile())
                    .setCredentialsProvider(createCredentials(authToken))
                    .call();

            return tempDir;

        } catch (GitAPIException e) {
            cleanup(tempDir);
            throw e;
        }
    }

    /**
     * Checkout specific branch or commit.
     *
     * @param repoPath Path to git repository
     * @param ref Branch name or commit SHA
     */
    public void checkout(Path repoPath, String ref) throws GitAPIException, IOException {

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repository = builder
                                        .setGitDir(repoPath.resolve(".git").toFile())
                                        .readEnvironment()
                                        .findGitDir()
                                        .build();

        try (Git git = new Git(repository)) {
            git.checkout()
                    .setName(ref)
                    .setForced(true)
                    .call();
        }
    }

    /**
     * Get commit SHA for a branch.
     */
    public String resolveCommit(Path repoPath, String ref) throws IOException {

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repository = builder
                                        .setGitDir(repoPath.resolve(".git").toFile())
                                        .readEnvironment()
                                        .findGitDir()
                                        .build();

        ObjectId objectId = repository.resolve(ref);
        return objectId != null ? objectId.getName() : null;
    }

    /**
     * Delete repository workspace.
     */
    public void cleanup(Path repoPath) throws IOException {
        if (Files.exists(repoPath)) {
            Files.walk(repoPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private org.eclipse.jgit.transport.CredentialsProvider createCredentials(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(token, "");
    }
}
