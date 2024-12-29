package net.onelitefeather.blackhole.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.profile.PunishProfileSimpleModule;
import net.onelitefeather.blackhole.api.punish.PunishEntrySimpleModule;
import net.onelitefeather.blackhole.api.template.PunishTemplate;
import net.onelitefeather.blackhole.api.template.PunishTemplateSimpleModule;
import net.onelitefeather.blackhole.request.template.TemplateWebRequests;
import net.onelitefeather.blackhole.web.BlackholeClient;

import java.util.UUID;

public class BanTheMeinerLP {
    public static void main(String[] args) {
        BlackholeClient blackholeClient = BlackholeClient.newClient("http://localhost:8080");
        TemplateWebRequests templateWebRequests = blackholeClient.templateRequests();

        PunishTemplate yolo = templateWebRequests.get(UUID.fromString("bf0e3349-8f7b-4f46-ae24-e061a14805ff")).orElseThrow();
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(PunishTemplateSimpleModule.INSTANCE)
                .registerModule(PunishEntrySimpleModule.INSTANCE)
                .registerModule(PunishProfileSimpleModule.INSTANCE)
                .registerModule(new Jdk8Module());
        System.out.println(objectMapper.valueToTree(yolo));

        var profile = PunishProfile.builder().owner("bfd1b89d097121dbd22042c75fffb78f8871761898d764d1586f0640a5cbf46a8d8280c66f62f50efbe56fc1e535009603b9a43b2e3f7f43533ceae48fc8550d").build();
//        PunishProfile add = blackholeClient.profileRequests().get(profile);
//        System.out.println(objectMapper.valueToTree(add));

        PunishProfile punishProfile = blackholeClient.punishRequests().add(profile.owner(), yolo.identifier(), UUID.randomUUID());

        System.out.println(objectMapper.valueToTree(punishProfile));
    }
}
