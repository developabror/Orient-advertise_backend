package uz.orientadvertise.services.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.orientadvertise.services.domain.model.DisabledTelegramChat;

public interface DisabledTelegramChatRepository extends JpaRepository<DisabledTelegramChat, Long> {
}
