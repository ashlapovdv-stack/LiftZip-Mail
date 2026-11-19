package ru.liftzip.mail

import android.content.Context
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundSchedulerDeviceTest {
    @Test fun automaticWorkStartsAfterNetworkReturnsWithoutReopeningMail(): Unit=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkNetworkRecovery")=="true")
        val app=ApplicationProvider.getApplicationContext<MailApplication>()
        val manager=WorkManager.getInstance(app)
        val connectivity=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val work=MailWorker.periodicRequest(15)
        val job="задание ${work.id.toString().take(8)}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario->
            try {
                scenario.moveToState(Lifecycle.State.CREATED)
                withTimeout(10_000){
                    app.backgroundJournal.events.first{it.lastOrNull{event->event.source==BackgroundSource.APP}?.stage==BackgroundStage.HIDDEN}
                }
                shell("svc wifi disable")
                shell("svc data disable")
                withTimeout(20_000){while(connectivity.activeNetwork!=null)delay(100)}
                manager.enqueueUniquePeriodicWork("test-network-recovery-${work.id}",ExistingPeriodicWorkPolicy.KEEP,work)
                    .result.get(10,TimeUnit.SECONDS)
                val waiting=withTimeout(10_000){manager.getWorkInfoByIdFlow(work.id).first{it!=null}}
                assertEquals(WorkInfo.State.ENQUEUED,waiting!!.state)
                assertTrue(app.backgroundJournal.events.value.none{it.detail==job})

                shell("svc wifi enable")
                shell("svc data enable")
                withTimeout(30_000){while(connectivity.activeNetwork==null)delay(100)}
                val after=withTimeout(55_000){app.backgroundJournal.events.first{events->
                    events.any{it.detail==job && it.stage==BackgroundStage.STARTED}
                }}
                val start=after.single{it.detail==job && it.stage==BackgroundStage.STARTED}
                assertEquals(BackgroundSource.AUTOMATIC,start.source)
                assertFalse("The periodic worker started with Mail still hidden",start.visible)
            } finally {
                shell("svc wifi enable")
                shell("svc data enable")
                manager.cancelWorkById(work.id).result.get(10,TimeUnit.SECONDS)
            }
        }
    }

    private fun shell(command: String) {
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use{it.readBytes()}
    }
}
