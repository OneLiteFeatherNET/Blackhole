package net.onelitefeather.blackhole.service

import net.onelitefeather.blackhole.spec.models.InjuryTemplate
import net.onelitefeather.blackhole.spec.repositories.InjuryTemplateRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * The class contains the business logic to interact with the [InjuryTemplateRepository] to handle different operations
 * which can be applied to templates
 * @author OneLiteFeather
 * @since 1.0.0
 */
@Service
class InjuryTemplateService {

    @Autowired
    private lateinit var injuryTemplateRepository: InjuryTemplateRepository

    private var cache: MutableMap<String, InjuryTemplate> = mutableMapOf()

    /**
     * Add a new [InjuryTemplate] to the database and the local application cache.
     * @param injuryTemplate the template which should be added
     */
    fun addTemplate(injuryTemplate: InjuryTemplate): InjuryTemplate {
        cache.putIfAbsent(injuryTemplate.name, injuryTemplate)

        return injuryTemplateRepository.save(injuryTemplate)
    }

    /**
     * Removes a [InjuryTemplate] from the database and cache by its corresponding name
     * @param name the name from the template
     * @return the deleted template
     */
    fun removeTemplate(name: String): InjuryTemplate {
        cache.remove(name)
        return injuryTemplateRepository.deleteByName(name)
    }

    /**
     * Get a [InjuryTemplate] by its name.
     * @param name the name from the template
     * @return the objects when there is a template which matches with the name otherwise null
     */
    fun getTemplateByName(name: String): InjuryTemplate? {
        val profile = cache[name] ?: injuryTemplateRepository.getTemplateByName(name).let {
            if (it.isPresent) {
                val template = it.get()
                cache[template.name] = template
                template
            } else null
        }
        return profile

    }

    /**
     * Returns a [List] which contains all available [InjuryTemplate].
     * @return the list with the objects
     */
    fun getAll(): List<InjuryTemplate> {
        if (cache.isNotEmpty()) {
            return cache.values.toList()
        }

        val templates = injuryTemplateRepository.findAll()

        templates.forEach {
            cache[it.name] = it
        }

        return templates.toList()
    }
}