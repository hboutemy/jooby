/*
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.internal.apt;

import static io.jooby.internal.apt.AnnotationSupport.findAnnotationValue;
import static java.util.stream.Collectors.joining;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.lang.model.element.*;

public enum ParameterGenerator {
  ContextParam("getAttribute", "io.jooby.annotation.ContextParam", "jakarta.ws.rs.core.Context") {
    @Override
    public String toSourceCode(
        boolean kt,
        MvcRoute route,
        AnnotationMirror annotation,
        TypeDefinition type,
        String name,
        boolean nullable) {
      if (type.is(Map.class)) {
        return CodeBlock.of(
            "java.util.Optional.ofNullable((java.util.Map) ctx.getAttribute(",
            CodeBlock.string(name),
            ")).orElseGet(() ->" + " ctx.getAttributes())");
      } else {
        return kt
            ? CodeBlock.of(
                "ctx.", method, "(", CodeBlock.string(name), ") as ", type.getRawType().toString())
            : CodeBlock.of(
                "(",
                type.getRawType().toString(),
                ") ctx.",
                method,
                "(",
                CodeBlock.string(name),
                ")");
      }
    }
  },
  CookieParam("cookie", "io.jooby.annotation.CookieParam", "jakarta.ws.rs.CookieParam"),
  FlashParam("flash", "io.jooby.annotation.FlashParam"),
  FormParam("form", "io.jooby.annotation.FormParam", "jakarta.ws.rs.FormParam"),
  HeaderParam("header", "io.jooby.annotation.HeaderParam", "jakarta.ws.rs.HeaderParam"),
  Lookup("lookup", "io.jooby.annotation.Param") {
    @Override
    protected Predicate<String> namePredicate() {
      return AnnotationSupport.NAME;
    }
  },
  PathParam("path", "io.jooby.annotation.PathParam", "jakarta.ws.rs.PathParam"),
  QueryParam("query", "io.jooby.annotation.QueryParam", "jakarta.ws.rs.QueryParam"),
  SessionParam("session", "io.jooby.annotation.SessionParam"),
  BodyParam("body") {
    @Override
    public String parameterName(AnnotationMirror annotation, String defaultParameterName) {
      // Body are unnamed
      return defaultParameterName;
    }

    @Override
    public String toSourceCode(
        boolean kt,
        MvcRoute route,
        AnnotationMirror annotation,
        TypeDefinition type,
        String name,
        boolean nullable) {
      var rawType = type.getRawType().toString();
      return switch (rawType) {
        case "byte[]" -> CodeBlock.of("ctx.body().bytes()");
        case "java.io.InputStream" -> CodeBlock.of("ctx.body().stream()");
        case "java.nio.channels.ReadableByteChannel" -> CodeBlock.of("ctx.body().channel()");
        default -> {
          if (type.isPrimitive()) {
            yield CodeBlock.of("ctx.", method, "().", type.getName(), "Value()");
          } else if (type.is(String.class)) {
            yield nullable
                ? CodeBlock.of("ctx.", method, "().valueOrNull()")
                : CodeBlock.of("ctx.", method, "().value()");
          } else if (type.is(Optional.class)) {
            yield CodeBlock.of(
                "ctx.", method, "().toOptional(", type.getArguments().get(0).toSourceCode(kt), ")");

          } else {
            yield CodeBlock.of("ctx.", method, "(", type.toSourceCode(kt), ")");
          }
        }
      };
    }
  },
  Bind("", "io.jooby.annotation.BindParam") {
    @Override
    public String parameterName(AnnotationMirror annotation, String defaultParameterName) {
      return defaultParameterName;
    }

    @Override
    public String toSourceCode(
        boolean kt,
        MvcRoute route,
        AnnotationMirror annotation,
        TypeDefinition type,
        String name,
        boolean nullable) {
      List<Element> converters = new ArrayList<>();
      var typeNames = findAnnotationValue(annotation, AnnotationSupport.VALUE);
      var typeName = typeNames.isEmpty() ? null : typeNames.get(0);
      var router = route.getRouter();
      var targetType = router.getTargetType();
      var env = route.getContext().getProcessingEnvironment();
      if (typeName != null) {
        converters.add(env.getElementUtils().getTypeElement(typeName));
      } else {
        // Fallback bean class first
        converters.add(env.getTypeUtils().asElement(type.getRawType()));
        // Fallback controller class later
        converters.add(targetType);
      }
      var fns = findAnnotationValue(annotation, "fn"::equals);
      var fn = fns.isEmpty() ? null : fns.get(0);
      Predicate<ExecutableElement> contextAsParameter =
          it ->
              it.getParameters().size() == 1
                  && it.getParameters().get(0).asType().toString().equals("io.jooby.Context");
      Predicate<ExecutableElement> matchesType =
          it -> {
            var returnType =
                new TypeDefinition(
                        route.getContext().getProcessingEnvironment().getTypeUtils(),
                        it.getReturnType())
                    .getRawType();
            return returnType.equals(type.getRawType())
                || (it.getSimpleName().toString().equals("<init>"));
          };
      var filter = contextAsParameter.and(matchesType);
      String methodErrorName;
      if (fn != null) {
        Predicate<ExecutableElement> matchesName = it -> it.getSimpleName().toString().equals(fn);
        filter = filter.and(matchesName);
        methodErrorName = fn + "(io.jooby.Context): " + type;
      } else {
        methodErrorName = "(io.jooby.Context): " + type;
      }
      // find function by type
      ExecutableElement mapping =
          converters.stream()
              .flatMap(it -> it.getEnclosedElements().stream())
              .filter(ExecutableElement.class::isInstance)
              .map(ExecutableElement.class::cast)
              // filter by Context
              .filter(filter)
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Method not found: " + methodErrorName + " on " + converters));
      if (!mapping.getModifiers().contains(Modifier.PUBLIC)) {
        throw new IllegalArgumentException(
            "Method is not public: " + mapping.getEnclosingElement() + "." + mapping);
      }
      if (mapping.getModifiers().contains(Modifier.STATIC)) {
        return CodeBlock.of(
            mapping.getEnclosingElement().asType() + "." + mapping.getSimpleName(), "(ctx)");
      } else {
        if (mapping.getEnclosingElement().equals(targetType)) {
          return CodeBlock.of("c." + mapping.getSimpleName(), "(ctx)");
        } else {
          if (mapping.getKind() == ElementKind.CONSTRUCTOR) {
            return CodeBlock.of(kt ? "" : "new ", type.getName(), "(ctx)");
          }
          throw new IllegalArgumentException(
              "Not a static method: " + mapping.getEnclosingElement() + "." + mapping);
        }
      }
    }
  };

  public String parameterName(AnnotationMirror annotation, String defaultParameterName) {
    return findAnnotationValue(annotation, namePredicate()).stream()
        .findFirst()
        .orElse(defaultParameterName);
  }

  protected Predicate<String> namePredicate() {
    return AnnotationSupport.VALUE;
  }

  public String toSourceCode(
      boolean kt,
      MvcRoute route,
      AnnotationMirror annotation,
      TypeDefinition type,
      String name,
      boolean nullable) {
    var paramSource = source(annotation);
    var builtin = builtinType(kt, annotation, type, name, nullable);
    if (builtin == null) {
      var toValue =
          CONTAINER.stream()
              .filter(type::is)
              .findFirst()
              .map(Class::getSimpleName)
              .map(it -> "to" + it)
              .orElse(nullable ? "toNullable" : "to");
      var elementType = type.getArguments().isEmpty() ? type : type.getArguments().get(0);
      if (paramSource.isEmpty() && BUILT_IN.stream().noneMatch(elementType::is)) {
        // for unsupported types, we check if node with matching name is present, if not we fallback
        // to entire scope converter
        if (kt) {
          return CodeBlock.of(
              "if(ctx.",
              method,
              "(",
              CodeBlock.string(name),
              ").isMissing()) ctx.",
              method,
              "().",
              toValue,
              "(",
              CodeBlock.type(kt, elementType.getName()),
              CodeBlock.clazz(kt),
              ") else ctx.",
              method,
              "(",
              CodeBlock.string(name),
              ").",
              toValue,
              "(",
              CodeBlock.type(kt, elementType.getName()),
              CodeBlock.clazz(kt),
              ")");
        } else {
          return CodeBlock.of(
              "ctx.",
              method,
              "(",
              CodeBlock.string(name),
              ").isMissing() ? ctx.",
              method,
              "().",
              toValue,
              "(",
              CodeBlock.type(kt, elementType.getName()),
              CodeBlock.clazz(kt),
              ") : ctx.",
              method,
              "(",
              CodeBlock.string(name),
              ").",
              toValue,
              "(",
              CodeBlock.type(kt, elementType.getName()),
              CodeBlock.clazz(kt),
              ")");
        }
      } else {
        // container of supported types: List<Integer>, Optional<UUID>
        if (elementType.is(String.class)) {
          return CodeBlock.of(
              "ctx.", method, "(", CodeBlock.string(name), paramSource, ").", toValue, "()");
        } else {
          return CodeBlock.of(
              "ctx.",
              method,
              "(",
              CodeBlock.string(name),
              paramSource,
              ").",
              toValue,
              "(",
              CodeBlock.type(kt, elementType.getName()),
              CodeBlock.clazz(kt),
              ")");
        }
      }
    } else {
      return builtin;
    }
  }

  protected String builtinType(
      boolean kt, AnnotationMirror annotation, TypeDefinition type, String name, boolean nullable) {
    if (BUILT_IN.stream().anyMatch(type::is)) {
      var paramSource = source(annotation);
      // look at named parameter
      if (type.isPrimitive()) {
        // like: .intValue
        return CodeBlock.of(
            "ctx.",
            method,
            "(",
            CodeBlock.string(name),
            paramSource,
            ").",
            CodeBlock.type(kt, type.getName()).toLowerCase(),
            "Value()");
      } else if (type.is(String.class)) {
        var stringValue = nullable ? "valueOrNull" : "value";
        // StringL: .value
        return CodeBlock.of(
            "ctx.", method, "(", CodeBlock.string(name), paramSource, ").", stringValue, "()");
      } else {
        var toValue = nullable ? "toNullable" : "to";
        // Any other type: .to(UUID.class)
        return CodeBlock.of(
            "ctx.",
            method,
            "(",
            CodeBlock.string(name),
            paramSource,
            ").",
            toValue,
            "(",
            CodeBlock.type(kt, type.getName()),
            CodeBlock.clazz(kt),
            ")");
      }
    }
    return null;
  }

  public static ParameterGenerator findByAnnotation(String annotation) {
    return Stream.of(values())
        .filter(it -> it.annotations.contains(annotation))
        .findFirst()
        .orElse(null);
  }

  ParameterGenerator(String method, String... annotations) {
    this.method = method;
    this.annotations = Set.of(annotations);
  }

  protected String source(AnnotationMirror annotation) {
    if (ParameterGenerator.Lookup.annotations.contains(annotation.getAnnotationType().toString())) {
      var sources = findAnnotationValue(annotation, AnnotationSupport.VALUE);
      return sources.isEmpty()
          ? ""
          : sources.stream()
              .map(it -> "io.jooby.ParamSource." + it)
              .collect(joining(", ", ", ", ""));
    }
    return "";
  }

  protected final String method;
  private final Set<String> annotations;
  private static final Set<Class> CONTAINER = Set.of(List.class, Set.class, Optional.class);
  private static final Set<String> BUILT_IN =
      Set.of(
          String.class.getName(),
          Boolean.class.getName(),
          Boolean.TYPE.getName(),
          Byte.class.getName(),
          Byte.TYPE.getName(),
          Character.class.getName(),
          Character.TYPE.getName(),
          Short.class.getName(),
          Short.TYPE.getName(),
          Integer.class.getName(),
          Integer.TYPE.getName(),
          Long.class.getName(),
          Long.TYPE.getName(),
          Float.class.getName(),
          Float.TYPE.getName(),
          Double.class.getName(),
          Double.TYPE.getName(),
          Enum.class.getName(),
          java.util.UUID.class.getName(),
          java.time.Instant.class.getName(),
          java.util.Date.class.getName(),
          java.time.LocalDate.class.getName(),
          java.time.LocalDateTime.class.getName(),
          java.math.BigDecimal.class.getName(),
          java.math.BigInteger.class.getName(),
          Duration.class.getName(),
          Period.class.getName(),
          java.nio.charset.Charset.class.getName(),
          "io.jooby.StatusCode",
          TimeZone.class.getName(),
          ZoneId.class.getName(),
          URI.class.getName(),
          URL.class.getName());
}
