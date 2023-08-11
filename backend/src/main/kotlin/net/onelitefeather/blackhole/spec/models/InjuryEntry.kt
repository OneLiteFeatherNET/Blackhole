package net.onelitefeather.blackhole.spec.models

import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.util.*

@Document
data class InjuryEntry(
    @Id
    val id: String,
    val playerUUID: UUID,
    val expired: Instant,
    val banTemplateRef: InjuryTemplate,
    val type: InjuryType,
    val softDelete: Boolean,
    // val extra: BitSet,
    @CreatedDate
    val created: Instant,
    @CreatedBy
    val createdBy: String,
    @LastModifiedDate
    val modified: Instant,
    @LastModifiedBy
    val modifiedBy: String,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InjuryEntry

        if (playerUUID != other.playerUUID) return false
        if (expired != other.expired) return false
        if (banTemplateRef != other.banTemplateRef) return false
        if (type != other.type) return false
      //  if (extra != other.extra) return false;

        return true
    }

    override fun hashCode(): Int {
        var result = playerUUID.hashCode()
        result = 31 * result + expired.hashCode()
        result = 31 * result + banTemplateRef.hashCode()
        result = 31 * result + type.hashCode()
        //result = 31 * result + extra.hashCode()
        return result
    }
}
