package uz.orientadvertise.services.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers a case-insensitive String-to-Enum converter for query/path parameters.
 *
 * <p>Spring's default String→Enum binding is case-sensitive — {@code ?status=ready}
 * fails to match the enum constant {@code READY} and the request 400s. The FE often
 * sends lowercase values from URL builders or stored filters; the API should treat
 * those identically to the canonical uppercase form.
 *
 * <p>The converter trims whitespace and uppercases before delegating to
 * {@link Enum#valueOf}. Truly invalid values still throw {@link IllegalArgumentException}
 * — only the case mismatch is forgiven, not typos.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new CaseInsensitiveEnumConverterFactory());
    }

    private static final class CaseInsensitiveEnumConverterFactory
            implements ConverterFactory<String, Enum<?>> {
        @Override
        public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
            return new CaseInsensitiveEnumConverter<>(targetType);
        }
    }

    private static final class CaseInsensitiveEnumConverter<T extends Enum<?>>
            implements Converter<String, T> {
        private final Class<T> enumType;

        CaseInsensitiveEnumConverter(Class<T> enumType) {
            this.enumType = enumType;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public T convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }
            return (T) Enum.valueOf((Class) enumType, source.trim().toUpperCase());
        }
    }
}
