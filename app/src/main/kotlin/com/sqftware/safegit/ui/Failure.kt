package com.sqftware.safegit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.sqftware.safegit.R
import com.sqftware.safegit.sync.Problem

/** What went wrong, and git's own words for it when there are any. */
@Composable
fun Failure(problem: Problem, detail: String) {
    Text(stringResource(problemText(problem)), color = MaterialTheme.colorScheme.error)
    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

private fun problemText(problem: Problem) = when (problem) {
    Problem.NOT_ON_BRANCH -> R.string.problem_not_on_branch
    Problem.NO_UPSTREAM -> R.string.problem_no_upstream
    Problem.FETCH -> R.string.problem_fetch
    Problem.PUSH -> R.string.problem_push
    Problem.REMOTE_KEPT_CHANGING -> R.string.problem_remote_kept_changing
    Problem.FOLDER_KEPT_CHANGING -> R.string.problem_folder_kept_changing
    Problem.FOLDER_BLOCKED -> R.string.problem_folder_blocked
    Problem.FOLDER_WRITE -> R.string.problem_folder_write
    Problem.FOLDER_REPO -> R.string.problem_folder_repo
    Problem.SUBMODULE -> R.string.problem_submodule
    Problem.GIT -> R.string.problem_git
}
