/*
 * User: Mike
 * Date: Sep 30, 2004
 * Time: 8:13:56 AM
 */
package com.xiplink.jira.git;

import com.atlassian.jira.InfrastructureException;
import com.atlassian.jira.util.JiraKeyUtils;
import com.opensymphony.module.propertyset.PropertySet;
import com.opensymphony.util.TextUtils;
import com.xiplink.jira.git.linkrenderer.GitLinkRenderer;
import com.xiplink.jira.git.linkrenderer.LinkFormatRenderer;
import com.xiplink.jira.git.linkrenderer.NullLinkRenderer;
import org.apache.commons.collections.map.LRUMap;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevFlag;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.eclipse.jgit.lib.Constants.R_REMOTES;
import static org.eclipse.jgit.lib.Constants.R_TAGS;

public class GitManagerImpl implements GitManager {
	private static final class GitDirectoryFilenameFilter implements FilenameFilter {
		public boolean accept(File dir, String name) {
			return "config".equals(name) || "refs".equals(name);
		}
	}

	private static Logger log = Logger.getLogger(GitManagerImpl.class);

	private GitLinkRenderer linkRenderer;
	private Map<String, RevCommit> logEntryCache;
	private Repository repository;

	private boolean active;
	private String inactiveMessage = "unknown";
	private final long id;

	private PropertySet properties;

	private ViewLinkFormat viewLinkFormat = null;
	private boolean isViewLinkSet = false;

	public GitManagerImpl(long id, PropertySet props) {
		this.id = id;
		this.properties = props;
		setup();
	}

	public synchronized void update(GProperties props) {
		deactivate("updating");

		GProperties.Util.fillPropertySet(props, properties);
		isViewLinkSet = false; /* If we don't reset this flag, we get svn-190 */

		setup();

	}

	public Map<String,String> getBranches(){
        Map<String,String> branches = new HashMap<String, String>();
		if(this.repository != null){
				Collection<Ref> refs = this.repository.getAllRefs().values();
				for (Ref ref : refs) {
					if(isRealHead(ref)){
						String branchId = ref.getObjectId().getName();
						String branchName = ref.getName();
                        String shortName = branchName.substring("refs/heads/".length());
                        branches.put(shortName, branchId);
					}
				}
		}
		return branches;
	}

    private boolean isRealHead(Ref ref) {
        if(!ref.isSymbolic()) {
            String refName = ref.getName();
            if(refName.length() > 10) {
                return refName.substring(5, 10).equalsIgnoreCase("heads");
            }
        }
        
        return false;
    }

	protected void setup() {
		// Now setup web link renderer
		linkRenderer = null;

		if (getViewLinkFormat() != null)
			linkRenderer = new LinkFormatRenderer(this);
		else
			linkRenderer = new NullLinkRenderer();

		// Now setup revision indexing if they want it
		if (isRevisionIndexing()) {
			// Setup the log message cache
			int cacheSize = 10000;

			if (getRevisioningCacheSize() > 0) {
				cacheSize = getRevisioningCacheSize();
			}

			logEntryCache = new LRUMap(cacheSize);
		}

		activate();
	}

    private RevCommit parseCommit(RevWalk walk, String revId) throws Exception {
        ObjectId rev = repository.resolve(revId);
        RevCommit commit = walk.parseCommit(rev);
        return commit;
    }

    private void markStart(RevWalk walk, String revId) throws Exception {
        RevCommit commit = parseCommit(walk, revId);
        walk.markStart(commit);
    }

    private void markUninteresting(RevWalk walk, String revId) throws Exception {
        RevCommit commit = parseCommit(walk, revId);
        walk.markUninteresting(commit);
    }

    public synchronized RevCommit getMergeBase(String baseId, String branchId) {
        RevCommit base = null;

        // if connection isn't up, don't even try
        if (!isActive()) {
            return base;
        }

		try {
			repository.scanForRepoChanges();

			if (log.isDebugEnabled()) {
				log.debug("Fetching merge base from repository=" + getRoot() + "  for " + baseId + " and " + branchId);
			}

			RevWalk walk = new RevWalk(repository);
            walk.setRevFilter(RevFilter.MERGE_BASE);

            markStart(walk, baseId);
            markStart(walk, branchId);

            Iterator<RevCommit> it = walk.iterator();
            while (it.hasNext()) {
                base = it.next();
            }

		} catch (Exception e) {
			log.error("Error retrieving changes from the repository.", e);
			deactivate(e.getMessage());
		}

		return base;
	}

	public synchronized Collection<RevCommit> getLogEntries(String fromRev, String toRev) {
		final Collection<RevCommit> logEntries = new ArrayList<RevCommit>();

		// if connection isn't up, don't even try
		if (!isActive()) {
			return logEntries;
		}

        
        if(toRev.equals(fromRev)) {
            return logEntries;
        }

		try {
			repository.scanForRepoChanges();

			if (log.isDebugEnabled()) {
				log.debug("Fetching log from repository=" + getRoot() + "  for " + fromRev + ".." + toRev);
			}

			RevWalk walk = new RevWalk(repository);

			if (fromRev != null) {
				markUninteresting(walk, fromRev);
			}

            markStart(walk, toRev);

			for (final RevCommit logEntry : walk) {
//				if (log.isDebugEnabled()) {
//					log.debug("Retrieved #" + logEntry.getId() + " : " + logEntry.getShortMessage());
//				}

				if (TextUtils.stringSet(logEntry.getFullMessage())
						&& JiraKeyUtils.isKeyInString(StringUtils.upperCase(logEntry.getFullMessage()))) {
					logEntries.add(logEntry);
				}
			}

		} catch (Exception e) {
			log.error("Error retrieving changes from the repository.", e);
			deactivate(e.getMessage());
		}
		// temp log comment
		if (log.isDebugEnabled()) {
			log.debug("Log entries size = " + logEntries.size() + " for " + getRoot());
		}
		return logEntries;
	}

	public synchronized RevCommit getLogEntry(String revision) {
		if (!isActive()) {
			throw new IllegalStateException("The connection to the repository is not active");
		}
		RevCommit logEntry = logEntryCache.get(revision);

		if (logEntry == null) {
			try {
				if (log.isDebugEnabled()) {
					log.debug("No cache - retrieving log message for revision: " + revision);
				}

				ObjectId retrieveStart = repository.resolve(revision);

				RevWalk walk = new RevWalk(repository);

				RevCommit entry = walk.parseCommit(retrieveStart);
				logEntry = entry;
				ensureCached(entry);

			} catch (Exception e) {
				log.error("Error retrieving logs: " + e, e);
				deactivate(e.getMessage());
				throw new InfrastructureException(e);
			}
		} else if (log.isDebugEnabled()) {
			log.debug("Found cached log message for revision: " + revision);
		}
		return logEntry;
	}

	public long getId() {
		return id;
	}

	/**
	 * Make sure a single log message is cached.
	 */
	private void ensureCached(RevCommit logEntry) {
		synchronized (logEntryCache) {
			logEntryCache.put(logEntry.getId().name(), logEntry);
		}
	}

	public PropertySet getProperties() {
		return properties;
	}

	public String getDisplayName() {
		return !properties.exists(MultipleGitRepositoryManager.GIT_REPOSITORY_NAME) ? getRoot() : properties
				.getString(MultipleGitRepositoryManager.GIT_REPOSITORY_NAME);
	}

	public String getOrigin() {
		return properties.getString(MultipleGitRepositoryManager.GIT_ORIGIN_KEY);
	}

	public String getRoot() {
		return properties.getString(MultipleGitRepositoryManager.GIT_ROOT_KEY);
	}

	public boolean isRevisionIndexing() {
		return properties.getBoolean(MultipleGitRepositoryManager.GIT_REVISION_INDEXING_KEY);
	}

	public int getRevisioningCacheSize() {
		return properties.getInt(MultipleGitRepositoryManager.GIT_REVISION_CACHE_SIZE_KEY);
	}

	public boolean isActive() {
		return active;
	}

	public String getInactiveMessage() {
		return inactiveMessage;
	}

	private static File findGitDir(String root) {
		File current = new File(root).getAbsoluteFile();
		while (current != null) {
			if (log.isDebugEnabled())
				log.debug("gitdir candidate: " + current.getAbsolutePath());
			final File gitDir = new File(current, ".git");
			if (isGitDirectory(gitDir) != null)
				return gitDir;
			current = current.getParentFile();
		}
		// see if it is a bare repository
		File testDirectory = new File(root + ".git");
		if (testDirectory.exists()) {
			File bareDirectory = isGitDirectory(testDirectory);
			if (bareDirectory == null) {
				testDirectory = new File(root);
				if (testDirectory.exists()) {
					bareDirectory = isGitDirectory(testDirectory);
				}
			}
			return bareDirectory;
		}
		return null;
	}

	// TODO funky logic - why?
	private static File isGitDirectory(File bareDirectory) {
		String[] list = bareDirectory.list(new GitDirectoryFilenameFilter());
		if (list == null)
		{
			log.error("Invalid file or IO access problems accessing git directory candidate: " + bareDirectory);			
		} else if (list.length > 1) {
			return bareDirectory;
		}
		return null;
	}

	public void activate() {
		File gitdir = findGitDir(getRoot());
		if (gitdir == null || !gitdir.isDirectory()) {
			log.error("Connection to git repository " + getRoot() + " failed: "
					+ (gitdir == null ? "gitdir is null" : "gitdir is a file"));
			// We don't want to throw an exception here because then the system
			// won't start if the repo is down or there is something wrong
			// with the configuration. We also still want this repository to
			// show up in our configuration so the user has a chance to fix
			// the problem.
			active = false;
			inactiveMessage = "Connection to git repository " + getRoot() + " failed: "
					+ (gitdir == null ? "gitdir is null" : "gitdir is a file");
			return;
		}
		try {
			repository = new Repository(gitdir);
		} catch (IOException e) {
			log.error("Connection to git repository " + getRoot() + " failed: " + e.getMessage(), e);
			// We don't want to throw an exception here because then the system
			// won't start if the repo is down or there is something wrong
			// with the configuration. We also still want this repository to
			// show up in our configuration so the user has a chance to fix
			// the problem.
			active = false;
			inactiveMessage = "Connection to git repository " + getRoot() + " failed: " + e.getMessage();
			return;
		}
		active = true;
	}

	private void deactivate(String message) {
		if (repository != null) {
			repository.close();
			repository = null;
		}
		active = false;
		inactiveMessage = message;
	}

	public ViewLinkFormat getViewLinkFormat() {
		if (!isViewLinkSet) {
			final String type = properties.getString(MultipleGitRepositoryManager.GIT_LINKFORMAT_TYPE);
			final String linkPathFormat = properties.getString(MultipleGitRepositoryManager.GIT_LINKFORMAT_PATH_KEY);
			final String changesetFormat = properties.getString(MultipleGitRepositoryManager.GIT_LINKFORMAT_CHANGESET);
			final String fileAddedFormat = properties.getString(MultipleGitRepositoryManager.GIT_LINKFORMAT_FILE_ADDED);
			final String fileModifiedFormat = properties
					.getString(MultipleGitRepositoryManager.GIT_LINKFORMAT_FILE_MODIFIED);
			final String fileReplacedFormat = properties
					.getString(MultipleGitRepositoryManager.GIT_LINKFORMAT_FILE_REPLACED);
			final String fileDeletedFormat = properties
					.getString(MultipleGitRepositoryManager.GIT_LINKFORMAT_FILE_DELETED);

			if (linkPathFormat != null || changesetFormat != null || fileAddedFormat != null
					|| fileModifiedFormat != null || fileReplacedFormat != null || fileDeletedFormat != null)
				viewLinkFormat = new ViewLinkFormat(type, changesetFormat, fileAddedFormat, fileModifiedFormat,
						fileReplacedFormat, fileDeletedFormat, linkPathFormat);
			else
				viewLinkFormat = null; /*
										 * [git-190] This could happen if the user clears all the fields in the Git
										 * repository web link configuration
										 */
			isViewLinkSet = true;
		}

		return viewLinkFormat;
	}

	public GitLinkRenderer getLinkRenderer() {
		return linkRenderer;
	}

	protected static String decryptPassword(String encrypted) throws IOException {
		if (encrypted == null)
			return null;

		BASE64Decoder decoder = new BASE64Decoder();
		byte[] result = decoder.decodeBuffer(encrypted);

		return new String(result, 0, result.length);
	}

	protected static String encryptPassword(String password) {
		if (password == null)
			return null;

		BASE64Encoder encoder = new BASE64Encoder();
		return encoder.encode(password.getBytes());
	}

	public FileDiff[] getFileDiffs(String revision) {
		try {
			RevWalk argWalk = new RevWalk(repository);

			argWalk.sort(RevSort.COMMIT_TIME_DESC, true);
			argWalk.sort(RevSort.BOUNDARY, true);

			AnyObjectId headId = repository.resolve(Constants.HEAD);
			RevCommit headCommit = argWalk.parseCommit(headId);
			RevCommit entry = argWalk.parseCommit(repository.resolve(revision));

			RevCommit c = entry;
			if (c.has(RevFlag.UNINTERESTING))
				argWalk.markUninteresting(c);
			else
				argWalk.markStart(c);

			return walk(headCommit, argWalk.next());
		} catch (Exception e) {
			log.error("Couldn't find filediffs for revision " + revision, e);
			return new FileDiff[0];
		}
	}

	private FileDiff[] walk(RevCommit headCommit, RevCommit entry) throws MissingObjectException,
			IncorrectObjectTypeException, CorruptObjectException, IOException {
		RevWalk currentWalk = new RevWalk(repository);
		currentWalk.sort(RevSort.COMMIT_TIME_DESC, true);
		currentWalk.sort(RevSort.BOUNDARY, true);

		try {
			currentWalk.markStart(headCommit);
		} catch (IOException e) {
			log.error("Couldn't find filediffs for head" + headCommit, e);
			throw e;
		}

		final TreeWalk fileWalker = new TreeWalk(repository);
		fileWalker.setRecursive(true);

		currentWalk.setTreeFilter(TreeFilter.ALL);
		fileWalker.setFilter(TreeFilter.ANY_DIFF);

		return FileDiff.compute(fileWalker, entry);
	}

	public void fetch() {

		try {
			Transport tn = Transport.open(repository, getOrigin());
			final FetchResult r;
			List<RefSpec> toget = new ArrayList<RefSpec>();
			toget.add(new RefSpec("refs/heads/*:refs/heads/*"));
			try {
				r = tn.fetch(new TextProgressMonitor(), toget);

				if (r.getTrackingRefUpdates().isEmpty()) {
					if (log.isDebugEnabled())
						log.debug("No updates");
					return;
				}
			} finally {
				tn.close();
			}

			boolean shownURI = false;
			for (final TrackingRefUpdate u : r.getTrackingRefUpdates()) {
				// if (//!verbose &&
				// u.getResult() == RefUpdate.Result.NO_CHANGE)
				// continue;

				final char type = shortTypeOf(u.getResult());
				final String longType = longTypeOf(u);
				final String src = abbreviateRef(u.getRemoteName(), false);
				final String dst = abbreviateRef(u.getLocalName(), true);

				if (!shownURI) {
					shownURI = true;
				}

				if (log.isDebugEnabled())
					log.debug(String.format(" %c %-17s %-10s -> %s", type, longType, src, dst));
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			log.warn("", e);
		}

	}

	private String longTypeOf(final TrackingRefUpdate u) {
		final RefUpdate.Result r = u.getResult();
		if (r == RefUpdate.Result.LOCK_FAILURE)
			return "[lock fail]";

		if (r == RefUpdate.Result.IO_FAILURE)
			return "[i/o error]";

		if (r == RefUpdate.Result.NEW) {
			if (u.getRemoteName().startsWith(Constants.R_HEADS))
				return "[new branch]";
			else if (u.getLocalName().startsWith(Constants.R_TAGS))
				return "[new tag]";
			return "[new]";
		}

		if (r == RefUpdate.Result.FORCED) {
			final String aOld = u.getOldObjectId().abbreviate(repository).toString();
			final String aNew = u.getNewObjectId().abbreviate(repository).toString();
			return aOld + "..." + aNew;
		}

		if (r == RefUpdate.Result.FAST_FORWARD) {
			final String aOld = u.getOldObjectId().abbreviate(repository).toString();
			final String aNew = u.getNewObjectId().abbreviate(repository).toString();
			return aOld + ".." + aNew;
		}

		if (r == RefUpdate.Result.REJECTED)
			return "[rejected]";
		if (r == RefUpdate.Result.NO_CHANGE)
			return "[up to date]";
		return "[" + r.name() + "]";
	}

	private static char shortTypeOf(final RefUpdate.Result r) {
		if (r == RefUpdate.Result.LOCK_FAILURE)
			return '!';
		if (r == RefUpdate.Result.IO_FAILURE)
			return '!';
		if (r == RefUpdate.Result.NEW)
			return '*';
		if (r == RefUpdate.Result.FORCED)
			return '+';
		if (r == RefUpdate.Result.FAST_FORWARD)
			return ' ';
		if (r == RefUpdate.Result.REJECTED)
			return '!';
		if (r == RefUpdate.Result.NO_CHANGE)
			return '=';
		return ' ';
	}

	protected String abbreviateRef(String dst, boolean abbreviateRemote) {
		if (dst.startsWith(R_HEADS))
			return dst.substring(R_HEADS.length());
		else if (dst.startsWith(R_TAGS))
			return dst.substring(R_TAGS.length());
		else if (abbreviateRemote && dst.startsWith(R_REMOTES))
			return dst.substring(R_REMOTES.length());
		return dst;
	}

}
