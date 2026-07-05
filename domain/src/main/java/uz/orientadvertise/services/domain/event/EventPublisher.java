package uz.orientadvertise.services.domain.event;

public interface EventPublisher {

    boolean publish(String message);
}
