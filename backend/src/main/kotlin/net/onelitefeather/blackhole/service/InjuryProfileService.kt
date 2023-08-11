package net.onelitefeather.blackhole.service

import net.onelitefeather.blackhole.spec.models.InjuryProfile
import net.onelitefeather.blackhole.spec.models.InjuryType
import net.onelitefeather.blackhole.spec.repositories.InjuryProfileRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class InjuryProfileService {

    @Autowired
    private lateinit var injureProfileRepo: InjuryProfileRepository

    private val cache: MutableMap<UUID, InjuryProfile> = mutableMapOf()

    private val defaultPoints: Double = 0.0

    fun getProfile(uuid: UUID): InjuryProfile {
        var profile = cache[uuid]

        if (profile != null) {
            return profile
        }

        profile = injureProfileRepo.findById(uuid.toString()).let { if (it.isPresent) it.get() else null }

        if (profile != null) {
            cache[uuid] = profile
            return profile
        }

        profile = InjuryProfile(uuid, defaultPoints, defaultPoints, mutableListOf())
        cache[uuid] = profile

        return injureProfileRepo.save(profile)
    }

    fun addInjury(uuid: UUID, type: InjuryType) {
        val profile = cache[uuid] ?: injureProfileRepo.findById(uuid.toString()).get()

        when (type) {
            InjuryType.CHAT -> handleChatInjury(profile)
            InjuryType.NETWORK -> handleNetworkInjury(profile)
        }
    }

    fun removeInjury(uuid: UUID, type: InjuryType) {
        var profile = cache[uuid] ?: injureProfileRepo.findById(uuid.toString()).get()

        profile = when (type) {
            InjuryType.CHAT -> profile.copy(activeChatTemplate = null)
            InjuryType.NETWORK -> profile.copy(activeNetworkTemplate = null)
        }

        injureProfileRepo.save(profile)

        cache[uuid] = profile
    }

    private fun handleNetworkInjury(profile: InjuryProfile) {
    }

    private fun handleChatInjury(profile: InjuryProfile) {

    }
}
