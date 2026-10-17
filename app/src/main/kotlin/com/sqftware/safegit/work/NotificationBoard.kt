package com.sqftware.safegit.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sqftware.safegit.MainActivity
import com.sqftware.safegit.R
import com.sqftware.safegit.repos.SavedRepo

/** Shows each repo's notice as an Android notification that opens the app. */
class NotificationBoard(private val context: Context) : NoticeBoard {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun show(repo: SavedRepo, notice: Notice) {
        // Creating a channel that exists already changes nothing, and keeps whatever the user set for it
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notice_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(text(notice))
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(repo.name)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Without the permission Android drops it, and the app still shows the result
        manager.notify(repo.id, ID, notification)
    }

    override fun clear(repo: SavedRepo) = manager.cancel(repo.id, ID)

    private fun text(notice: Notice) = when (notice) {
        Notice.CONFLICT -> R.string.notice_conflict
        Notice.CONFLICT_AHEAD -> R.string.notice_conflict_ahead
        Notice.CHOOSE -> R.string.notice_choose
        Notice.SIGN_IN -> R.string.notice_sign_in
        Notice.FAILING -> R.string.notice_failing
    }

    private companion object {
        const val CHANNEL = "sync"
        const val ID = 1
    }
}
