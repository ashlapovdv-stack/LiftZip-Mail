package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class BackgroundHealthTest {
    private fun summary(access: NotificationAccess=NotificationAccess(),poll: PollSettings=PollSettings(),runtime: BackgroundRuntime=BackgroundRuntime("ENQUEUED"),bg: BackgroundCheck=BackgroundCheck(),now: Long=1000)=
        BackgroundHealthRules.summary(poll,access,runtime,bg,now)
    @Test fun notificationDenialIsNotReportedAsAProhibitionOnMailPolling() {
        assertEquals(summary(),summary(NotificationAccess(permission=false,appEnabled=false,channelEnabled=false)))
    }
    @Test fun explicitlyDisabledPollingIsClearlyIdentified() {assertTrue(summary(poll=PollSettings(backgroundMinutes=0)).contains("выключена"))}
    @Test fun backgroundBatteryRestrictionIsDistinguishedFromServerFailures() {assertTrue(summary(NotificationAccess(backgroundRestricted=true)).contains("Android ограничивает"))}
    @Test fun meteredDataRestrictionIsNamed() {assertTrue(summary(NotificationAccess(dataRestricted=true)).contains("трафика"))}
    @Test fun lostPeriodicJobOffersRestoration() {assertTrue(summary(runtime=BackgroundRuntime("STOPPED")).contains("Восстановить"))}
    @Test fun aManualTestCannotPretendThatAutomaticPollingSucceeded() {
        assertFalse(summary(bg=BackgroundCheck(finished=900,result="success",kind="test")).contains("выполнялась успешно"))
        assertTrue(summary(bg=BackgroundCheck(automaticFinished=900,automaticResult="success")).contains("выполнялась успешно"))
    }
    @Test fun staleAutomaticRunsAndDozeAreNotDescribedAsInstantDelivery() {
        assertTrue(summary(bg=BackgroundCheck(automaticFinished=1,automaticResult="success"),now=2_000_000).contains("Давно"))
        assertTrue(summary(NotificationAccess(deviceIdle=true)).contains("отложить"))
    }
    @Test fun stopReasonsNameSystemQuotaAndPermissionRestrictionsWithoutGuessingUnknownCodes() {
        assertTrue(BackgroundHealthRules.stopReason(10)!!.contains("время"))
        assertTrue(BackgroundHealthRules.stopReason(11)!!.contains("запретил"))
        assertTrue(BackgroundHealthRules.stopReason(777)!!.contains("777"))
        assertNull(BackgroundHealthRules.stopReason(null))
    }
}
