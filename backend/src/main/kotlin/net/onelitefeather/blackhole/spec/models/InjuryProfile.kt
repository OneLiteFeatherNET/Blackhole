package net.onelitefeather.blackhole.spec.models

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.DBRef
import org.springframework.data.mongodb.core.mapping.Document
import java.util.UUID


/**
 * @author theEvilReaper
 * @version 1.0.0
 * @since
 **/

@Document
data class InjuryProfile(
    @Id
    val playerUUID: UUID,
    val chatPoints: Double,
    val banPoints: Double,
    @DBRef
    val history: List<InjuryEntry>,
    val activeChatTemplate: InjuryTemplate? = null,
    val activeNetworkTemplate: InjuryTemplate? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InjuryProfile

        if (playerUUID != other.playerUUID) return false
        if (chatPoints != other.chatPoints) return false
        if (banPoints != other.banPoints) return false
        if (history != other.history) return false

        return true
    }

    override fun hashCode(): Int {
        var result = playerUUID.hashCode()
        result = 31 * result + chatPoints.hashCode()
        result = 31 * result + banPoints.hashCode()
        result = 31 * result + history.hashCode()
        return result
    }
}
