package alien4cloud.plugin.marathon.service.builders;

/**
 * @author Adrian Fraisse
 */
public abstract class Builder<T> {
    T elem;
    abstract T build();
}
