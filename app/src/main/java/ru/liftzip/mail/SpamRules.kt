package ru.liftzip.mail

object SpamRules {
    fun junk(m: MailItem)=m.localFolder=="remote-junk" || FolderRules.role(m.remoteFolder,emptyList())==FolderRole.JUNK
    fun eligible(m: MailItem,toInbox: Boolean)=FolderRules.remote(m) && !m.pendingDelete && !FolderRules.draft(m) && !FolderRules.sent(m) &&
        m.localFolder!="remote-trash" && junk(m)==toInbox
    fun destination(folders: List<TrashFolderInfo>,role: FolderRole): String? {
        require(role==FolderRole.JUNK || role==FolderRole.INBOX)
        val usable=folders.filter{it.attributes.none{a->a.equals("\\NoSelect",true)}}
        return if(role==FolderRole.INBOX)usable.firstOrNull{it.name.equals("INBOX",true)}?.name
        else usable.firstOrNull{it.attributes.any{a->a.equals("\\Junk",true)}}?.name
            ?:usable.firstOrNull{FolderRules.role(it.name,it.attributes)==FolderRole.JUNK}?.name
    }
}
