package net.onelitefeather.blackhole.handler

import jakarta.websocket.server.PathParam
import net.onelitefeather.blackhole.service.InjuryTemplateService
import net.onelitefeather.blackhole.spec.models.InjuryTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException


/**
 * @author theEvilReaper
 * @version 1.0.0
 * @since
 **/

@RestController
class InjuryTemplateHandler(
    private var templateService: InjuryTemplateService
) {

    @RequestMapping("/template/", method = [RequestMethod.PUT], produces = ["application/json"])
    fun add(@RequestBody template: InjuryTemplate): ResponseEntity<InjuryTemplate> {
        return ResponseEntity.ok(templateService.addTemplate(template))
    }

    @RequestMapping("/template/{id}", method = [RequestMethod.GET], produces = ["application/json"])
    fun get(@PathParam("id") id: String): ResponseEntity<InjuryTemplate> {
        if (id.trim().isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The given id from a template can't be empty")
        }

        templateService.getTemplateByName(id).let {
            if (it != null) {
                return ResponseEntity.ok(it)
            } else {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "There is no template which matches with the id: $id")
            }
        }
    }

    @RequestMapping("/template/remove/{id}", method = [RequestMethod.DELETE], produces = ["application/json"])
    fun remove(@PathParam("id") id: String): ResponseEntity<InjuryTemplate> {
        if (id.trim().isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The given id from a template can't be empty")
        }
        return ResponseEntity.ok(templateService.removeTemplate(id))
    }

    @RequestMapping("/template/update/", method = [RequestMethod.PUT], produces = ["application/json"])
    fun update(@PathParam("id") id: String): ResponseEntity<InjuryTemplate> {
        TODO("Implement")
    }


    @RequestMapping("/template/getAll", method = [RequestMethod.GET], produces = ["application/json"])
    fun getAll(): ResponseEntity<List<InjuryTemplate>> {
        return ResponseEntity.ok(templateService.getAll())
    }
}