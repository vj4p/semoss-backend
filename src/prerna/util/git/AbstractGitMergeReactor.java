/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.util.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import prerna.sablecc2.om.execptions.SemossPixelException;

/**
 * Merges one local branch into the currently checked-out branch.
 *
 * <p>
 * This is the piece that lets an agent work on a branch and a human land it:
 * {@code CreateBranch} → agent works → {@code Diff} to review → merge or walk
 * away. Without it a branch could be made and inspected but never integrated.
 *
 * <h3>Why this does not use {@code GitMergeHelper}</h3>
 * <p>
 * {@link GitMergeHelper#merge} already existed and was never called by anything.
 * It is unsuitable here for two reasons, both of which matter more than the code
 * it would have saved. It returns {@code void} and swallows every git exception,
 * so a caller cannot tell success from failure. And on a conflict it
 * <em>deletes the conflicting files</em> and retries, which is data loss
 * disguised as conflict resolution — the opposite of what a review flow wants.
 *
 * <h3>Conflicts</h3>
 * <p>
 * A conflicting merge is reported, not resolved. The working tree is left in the
 * conflicted state so the existing {@code ResolveConflict} reactor can act on it,
 * and the result carries {@code mergeStatus: "CONFLICTING"} plus the conflicting
 * paths. Nothing is deleted and nothing is committed on the caller's behalf.
 */
public abstract class AbstractGitMergeReactor extends AbstractGitWorktreeReactor {

	private static final String BRANCH_KEY = "branch";
	private static final String MESSAGE_KEY = "message";
	private static final String FAST_FORWARD_ONLY_KEY = "fastForwardOnly";

	private String branch;
	private String message;
	private boolean fastForwardOnly;

	protected AbstractGitMergeReactor(GitReactorTarget target) {
		super(target, new String[] { BRANCH_KEY, MESSAGE_KEY, FAST_FORWARD_ONLY_KEY }, new int[] { 1, 0, 0 });
	}

	@Override
	protected boolean requiresEditPermission() {
		return true;
	}

	@Override
	protected void validateOperationInput() {
		String branchValue = this.keyValue.get(BRANCH_KEY);
		if (branchValue == null || (branchValue = branchValue.trim()).isEmpty()) {
			throw new SemossPixelException("Must pass in the branch to merge");
		}
		if (!GitBranchUtils.isValidBranchName(branchValue)) {
			throw new SemossPixelException("'" + branchValue + "' is not a valid branch name");
		}
		this.branch = branchValue;

		String messageValue = this.keyValue.get(MESSAGE_KEY);
		this.message = messageValue == null || messageValue.trim().isEmpty() ? null : messageValue.trim();

		this.fastForwardOnly = Boolean.parseBoolean(this.keyValue.get(FAST_FORWARD_ONLY_KEY));
	}

	@Override
	protected Map<String, Object> runGitOperation(Git thisGit, GitTargetHandle handle) throws Exception {
		Repository repo = thisGit.getRepository();
		final String branchName = this.branch;

		Ref sourceRef = repo.findRef(Constants.R_HEADS + branchName);
		if (sourceRef == null) {
			throw new SemossPixelException("Branch '" + branchName + "' does not exist");
		}

		String currentBranch = repo.getBranch();
		if (branchName.equals(currentBranch)) {
			throw new SemossPixelException(
					"'" + branchName + "' is already the checked-out branch; check out the target branch first");
		}

		// Refuse to start a merge over uncommitted work. JGit would either fail deep
		// inside the merge or quietly fold those edits into the merge commit, and both
		// are worse than saying so up front.
		//
		// Deliberately keyed off staged and unstaged only, not the `clean` flag, which
		// is also false for untracked files. Untracked files do not participate in a
		// merge, and a project always has some — `.gitignore` and
		// `assets/.admin/social.properties` are untracked from the moment it is
		// created, so testing `clean` here would refuse every merge that ever ran.
		GitStatusUtils.GitStatusResult before = GitStatusUtils.computeStatus(repo);
		if (!before.conflicted.isEmpty()) {
			throw new SemossPixelException(
					"A merge is already in progress with unresolved conflicts. Resolve or abort it first.");
		}
		if (!before.staged.isEmpty() || !before.unstaged.isEmpty()) {
			throw new SemossPixelException("There are uncommitted changes on '" + currentBranch
					+ "'. Commit or discard them before merging.");
		}

		// Same guard the branch reactors apply: a ref carrying a symlink can escape the
		// version folder once checked out.
		GitRepoUtils.assertNoSymlinks(repo, sourceRef.getObjectId(), branchName);

		MergeResult result;
		try {
			MergeCommand mc = thisGit.merge().include(sourceRef);
			if (this.message != null) {
				mc.setMessage(this.message);
			}
			mc.setFastForward(this.fastForwardOnly ? MergeCommand.FastForwardMode.FF_ONLY
					: MergeCommand.FastForwardMode.FF);
			result = mc.call();
		} catch (CheckoutConflictException e) {
			List<String> conflicts = new ArrayList<>(e.getConflictingPaths());
			Collections.sort(conflicts);
			throw new SemossPixelException("Cannot merge '" + branchName
					+ "': local changes would be overwritten in: " + String.join(", ", conflicts)
					+ ". Commit, stage, or discard these changes first.");
		}

		MergeResult.MergeStatus status = result.getMergeStatus();
		List<String> conflicting = new ArrayList<>();
		if (result.getConflicts() != null) {
			conflicting.addAll(result.getConflicts().keySet());
			Collections.sort(conflicting);
		}

		// Only push a successful merge. A conflicted tree is a local, in-progress state
		// and replicating it would hand the same half-merge to every node.
		if (status.isSuccessful()) {
			handle.pushToCluster();
		}

		ObjectId headCommitId = repo.resolve(Constants.HEAD);
		Map<String, Object> resultMap = new LinkedHashMap<>();
		resultMap.put("merged", status.isSuccessful());
		resultMap.put("mergeStatus", status.name());
		resultMap.put("branch", branchName);
		resultMap.put("into", currentBranch);
		resultMap.put("headCommitId", headCommitId == null ? null : headCommitId.getName());
		resultMap.put("conflicts", conflicting);
		resultMap.put("status", GitReactorResponseUtils.buildStatusMap(GitStatusUtils.computeStatus(repo)));

		return resultMap;
	}

	@Override
	protected String getOperationLogPhrase() {
		return "merging branch";
	}

	@Override
	protected String getOperationErrorMessage() {
		return "Error occurred merging the branch.";
	}

	@Override
	public String getReactorDescription() {
		return "This reactor merges a local branch into the currently checked-out branch of "
				+ this.target.getLabelWithArticle()
				+ "'s git repository. Conflicts are reported rather than resolved: the working tree is left conflicted"
				+ " and nothing is deleted.";
	}

	@Override
	protected String getDescriptionForKey(String key) {
		if (key.equals(BRANCH_KEY)) {
			return "The name of the branch to merge in";
		} else if (key.equals(MESSAGE_KEY)) {
			return "Optional commit message for the merge commit";
		} else if (key.equals(FAST_FORWARD_ONLY_KEY)) {
			return "Optional; when true the merge only succeeds if it can fast-forward, leaving history linear";
		}
		return super.getDescriptionForKey(key);
	}
}
