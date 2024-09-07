package org.example;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.NullOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class JGitImpl {
    private static Git git;
    private static String remoteUrl = "<REPO_URL>";
    private static String userName = "<USERNAME>";
    private static String password = "<PAT>";
    private static CredentialsProvider credentialsProvider;
    private static String repoPath = "<PATH_TO_LOCAL_REPO>";

    JGitImpl() {
        //NO-OP
    }

    public static void main(String[] args) throws GitAPIException, IOException {
        git = Git.open(new File(repoPath)); //Assume path is sent to constructor
        credentialsProvider = new UsernamePasswordCredentialsProvider(userName, password);

    }


    /****
     * Commit and push to remote, return result according to the status
     * @param repoPath
     * @param filePath
     * @param fileContent
     * @param commitMessage
     * @param username
     * @param password
     * @return
     */
    public static String commitAndPushFile(String repoPath, String filePath, String fileContent, String commitMessage, String username, String password) {
        try (Git git = Git.open(new File(repoPath))) {
            Repository repository = git.getRepository();

            // Step 1: Write content to the file
            File file = new File(filePath);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(fileContent);
            }

            // Step 2: Stage and commit the file
            git.add().addFilepattern(file.getName()).call();
            git.commit().setMessage(commitMessage).call();

            // Step 3: Push the changes to the remote
            Iterable<PushResult> pushResults = git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
                    .setRefSpecs(new RefSpec("refs/heads/master"))  // or target branch
                    .call();

            // Step 4: Analyze push results
            for (PushResult pushResult : pushResults) {
                if (pushResult.getRemoteUpdates().isEmpty()) {
                    // If successful push
                    return "";
                } else if (pushResult.getMessages().contains("non-fast-forward") || pushResult.getMessages().contains("rejected")) {
                    // If conflict detected, compare with the remote version
                    return compareWithRemote(git, filePath);
                }
            }
        } catch (TransportException e) {
            return "Conflict with remote repository";
        } catch (GitAPIException | IOException e) {
            return "An error occurred: " + e.getMessage();
        }
        return "Other reason for failure";
    }

    /****
     * Compare current version with remote.
     * @param git
     * @param filePath
     * @return
     * @throws IOException
     * @throws GitAPIException
     */

    private static String compareWithRemote(Git git, String filePath) throws IOException, GitAPIException {
        Repository repository = git.getRepository();
        // Retrieve the latest commit
        ObjectId headId = repository.resolve("refs/remotes/origin/master^{tree}");
        ObjectId localId = repository.resolve("HEAD^{tree}");

        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit remoteCommit = revWalk.parseCommit(headId);
            RevCommit localCommit = revWalk.parseCommit(localId);

            // Compare local file with remote file
            DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE);
            diffFormatter.setRepository(repository);

            StringBuilder diffOutput = new StringBuilder();
            diffFormatter.format(localCommit.getTree(), remoteCommit.getTree());

            diffOutput.append("Conflict detected. Differences between local and remote:\n");
            diffFormatter.format(localCommit.getTree(), remoteCommit.getTree());

            return diffOutput.toString();
        }
    }


    /*****
     * Compare with some version using commit hash
     * @param commitHash
     * @param filePath
     * @return
     */
    public static String getDiffWithCommit(String commitHash, String filePath) {
        try (Git git = Git.open(new File("."))) {
            Repository repository = git.getRepository();

            // Get the tree from the specified commit
            ObjectId commitId = repository.resolve(commitHash);
            if (commitId == null) {
                throw new IllegalArgumentException("Invalid commit hash");
            }

            RevWalk revWalk = new RevWalk(repository);
            RevCommit commit = revWalk.parseCommit(commitId);
            revWalk.close();

            // Get the tree of the specified commit
            CanonicalTreeParser oldTreeParser = prepareTreeParser(repository, commit.getTree().getId());

            // Prepare a TreeWalk for the current working directory (HEAD)
            ObjectId headCommitId = repository.resolve("HEAD");
            RevCommit headCommit = revWalk.parseCommit(headCommitId);
            CanonicalTreeParser newTreeParser = prepareTreeParser(repository, headCommit.getTree().getId());

            // Use DiffFormatter to compute the diff
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter diffFormatter = new DiffFormatter(out)) {
                diffFormatter.setRepository(repository);
                diffFormatter.setDiffComparator(org.eclipse.jgit.diff.RawTextComparator.DEFAULT);
                diffFormatter.setDetectRenames(true);

                // Compute the diff between the current working directory and the given commit
                diffFormatter.format(oldTreeParser, newTreeParser);

                // Get the diff output
                String diffOutput = out.toString(String.valueOf(StandardCharsets.UTF_8));
                System.out.println("Diff output:\n" + diffOutput);
                return diffOutput;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    // Helper method to prepare the TreeParser for a specific commit tree
    private static CanonicalTreeParser prepareTreeParser(Repository repository, ObjectId treeId) throws Exception {
        // Create a new tree walk to traverse the commit's tree
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(treeId);
            treeWalk.setRecursive(true);

            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            treeParser.reset(repository.newObjectReader(), treeId);

            return treeParser;
        }
    }



    /*******
     * Get content of a commit --> Restore from version history
     * @param commitHash
     * @return
     */
    public static String getVersionByCommitHash(String commitHash) {
        try (Git git = Git.open(new File("."))) {
            Repository repository = git.getRepository();

            // Use RevWalk to find the commit by its hash
            ObjectId commitId = repository.resolve(commitHash);
            if (commitId == null) {
                return null; // Invalid commit hash
            }

            RevWalk revWalk = new RevWalk(repository);
            RevCommit commit = revWalk.parseCommit(commitId);
            revWalk.close();

            // Get the commit's tree
            RevTree tree = commit.getTree();

            // Use TreeWalk to retrieve the file content from the specific commit
            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);

                while (treeWalk.next()) {
                    //TODO:: get contents of all files in the commit
                    if (treeWalk.getPathString().equals("hamada.txt")) {
                        // Read the content of the file at the specified commit
                        ObjectId objectId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repository.open(objectId);
                        byte[] bytes = loader.getBytes();
                        String content = new String(bytes, StandardCharsets.UTF_8);
                        System.out.println("File content at commit " + commitHash + ":\n" + content);
                        return content;
                    }
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    /***
     * Get Remote History
     * @param repoPath
     * @param remoteBranch
     * @throws IOException
     * @throws GitAPIException
     */
    public static void getRemoteBranchHistory(String repoPath, String remoteBranch) throws IOException, GitAPIException {
        try (Git git = Git.open(new File(repoPath))) {
            // Fetch the latest changes from the remote
            git.fetch().setRemote("origin").call();

            // Get the reference to the remote branch
            String branchRef = "refs/remotes/origin/" + remoteBranch;
            Ref branch = git.getRepository().findRef(branchRef);

            // Iterate through the commit history
            Iterable<RevCommit> commits = git.log().add(branch.getObjectId()).call();
            for (RevCommit commit : commits) {
                System.out.println("Commit: " + commit.getName());
                System.out.println("Author: " + commit.getAuthorIdent().getName());
                System.out.println("Date: " + commit.getAuthorIdent().getWhen());
                System.out.println("Message: " + commit.getFullMessage());
                System.out.println("-------------------------------");
            }
        }
    }

    /*****
     *  Fetch all branches from remote repository
     * @param repoUrl
     * @param username
     * @param token
     */
    public static void fetchAllBranches(String repoUrl, String username, String token) {
        try {
            // Fetch all remote branches (without cloning the repository)
            Ref[] remoteBranches = (Git.lsRemoteRepository()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token))
                    .setHeads(true)  // Only fetch branches (heads)
                    .setRemote(repoUrl)
                    .call()).toArray(new Ref[0]);

            // Display the branch names in a clean format
            System.out.println("Branches in remote repository:");
            for (Ref branch : remoteBranches) {
                String branchName = branch.getName().substring(branch.getName().lastIndexOf('/') + 1);
                System.out.println(branchName);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*****
     * Get Version using branch name and commit hash
     * @param branchName
     * @param commitHash
     * @throws GitAPIException
     * @throws IOException
     */
    private static void getVersion(String branchName, String commitHash) throws GitAPIException, IOException {
        // Fetch the branch from the remote repository
        FetchResult fetchResult = git.fetch()
                .setRemote("origin")
                .setRefSpecs("refs/heads/" + branchName + ":refs/remotes/origin/" + branchName)
                .call();

        RevCommit commit = git.getRepository().parseCommit(ObjectId.fromString(commitHash));

        RevTree tree = commit.getTree();
        Repository repository = git.getRepository();

        try (RevWalk revWalk = new RevWalk(repository)) {

            if (commit.getParentCount() > 0) {
                RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());

                CanonicalTreeParser parentTreeIter = new CanonicalTreeParser();
                try (ObjectReader reader = repository.newObjectReader()) {
                    parentTreeIter.reset(reader, parent.getTree());
                }

                CanonicalTreeParser commitTreeIter = new CanonicalTreeParser();
                try (ObjectReader reader = repository.newObjectReader()) {
                    commitTreeIter.reset(reader, commit.getTree());
                }

                try (DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE)) {
                    diffFormatter.setRepository(repository);
                    List<DiffEntry> diffs = diffFormatter.scan(parentTreeIter, commitTreeIter);

                    for (DiffEntry diff : diffs) {
                        System.out.println("Change: " + diff.getChangeType() + " " + diff.getNewPath());

                        if (diff.getChangeType() != DiffEntry.ChangeType.DELETE) {
                            ObjectId blobId = diff.getNewId().toObjectId();
                            ObjectLoader loader = repository.open(blobId);

                            System.out.println("Content of " + diff.getNewPath() + ":");
                            loader.copyTo(System.out);  // Print content to stdout
                            System.out.println();  // Add a newline for clarity
                        }
                    }
                }
            } else {
                System.out.println("No parent found. This might be the initial commit.");
            }
        }
    }
}
