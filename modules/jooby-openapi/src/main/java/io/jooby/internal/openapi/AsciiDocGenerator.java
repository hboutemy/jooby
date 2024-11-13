/*
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.internal.openapi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.tags.Tag;

public class AsciiDocGenerator {
  public static String generate(OpenAPI openAPI) throws IOException {
    var sb = new StringBuilder();
    /* Info: */
    intro(openAPI, sb);
    paths(openAPI, sb);
    Files.write(Paths.get("target", "out.adoc"), sb.toString().getBytes(StandardCharsets.UTF_8));
    Asciidoctor asciidoctor = Asciidoctor.Factory.create();
    var css = Paths.get(System.getProperty("java.io.tmpdir"), "openapi-asciidoc.css");

    Files.write(css, css(), StandardOpenOption.CREATE);
    Attributes attributes =
        Attributes.builder()
            //        .backend("pdf")
            .attribute("toc", "numbered")
            .attribute("stylesheet", css.toAbsolutePath().toString())
            .experimental(true)
            //            .backend("pdf")
            .build();
    asciidoctor.convertFile(
        Paths.get("target", "out.adoc").toFile(),
        Options.builder().toFile(true).attributes(attributes).safe(SafeMode.UNSAFE).build());
    return sb.toString();
  }

  private static byte[] css() throws IOException {
    try (var in = AsciiDocGenerator.class.getResourceAsStream("/asciidoc.css")) {
      return in.readAllBytes();
    }
  }

  record Route(String method, String pattern, Operation operation) {}

  private static void paths(OpenAPI node, StringBuilder out) {
    out.append("== ")
        .append("Operations")
        .append(System.lineSeparator())
        .append(System.lineSeparator());
    var paths = node.getPaths();
    Map<String, List<Route>> tags = new LinkedHashMap<>();
    paths.forEach(
        (pattern, path) -> {
          Map<String, Operation> routes = new LinkedHashMap<>();
          routes.put("GET", path.getGet());
          routes.put("POST", path.getPost());
          routes.put("PUT", path.getPut());
          routes.put("PATCH", path.getPatch());
          routes.put("DELETE", path.getDelete());
          routes.put("HEAD", path.getHead());
          routes.put("OPTIONS", path.getOptions());
          routes.put("TRACE", path.getTrace());
          routes.forEach(
              (method, operation) -> {
                nonnullOp(method, pattern, operation)
                    .ifPresent(
                        route -> {
                          Optional.ofNullable(route.operation().getTags())
                              .filter(it -> !it.isEmpty())
                              .orElse(List.of("*"))
                              .forEach(
                                  tag -> {
                                    tags.computeIfAbsent(tag, key -> new ArrayList<>()).add(route);
                                  });
                        });
              });
        });
    tags.entrySet().stream()
        .filter(it -> !it.equals("*"))
        .forEach(
            e -> {
              tag(node, e.getKey(), e.getValue(), out);
            });
  }

  private static void tag(OpenAPI api, String key, List<Route> routes, StringBuilder out) {
    var tag =
        api.getTags().stream()
            .filter(it -> it.getName().equalsIgnoreCase(key))
            .findFirst()
            .orElseGet(() -> new Tag().name(key));
    out.append("=== ")
        .append(tag.getName())
        .append(System.lineSeparator())
        .append(System.lineSeparator());

    withProperty(
        tag,
        Tag::getDescription,
        description -> out.append(description).append(System.lineSeparator()));
    for (Route route : routes) {
      var deprecated = route.operation().getDeprecated();
      var style = "[.method";
      if (deprecated == Boolean.TRUE) {
        style += " .deprecated";
      }
      style += "]";
      out.append(System.lineSeparator());
      out.append("[TIP.")
          .append(route.method.toLowerCase())
          .append(", caption=")
          .append(route.method)
          .append("]")
          .append(System.lineSeparator());
      out.append(".")
          .append(style)
          .append("#")
          .append(route.pattern)
          .append("# ")
          .append(route.operation.getSummary())
          .append(System.lineSeparator());
      var description = route.operation().getDescription();
      out.append(description == null ? " " : description)
          .append("&nbsp;")
          .append(System.lineSeparator());
      if (route.operation.getParameters() != null) {
        out.append(System.lineSeparator()).append("* Parameters").append(System.lineSeparator());
        for (Parameter parameter : route.operation.getParameters()) {
          out.append("** ")
              .append(parameter.getName())
              .append(": ")
              .append(parameter.getDescription())
              .append(System.lineSeparator());
          var schema = parameter.getSchema();
          var schemaString = schema.getType();
          if (schema.getType().equals("array")) {
            schemaString += " of " + schema.getItems().getType();
          }
          out.append("*** ").append("type: ").append(schemaString).append(System.lineSeparator());
          out.append("*** ")
              .append("in: ")
              .append(parameter.getIn())
              .append(System.lineSeparator());
          out.append("*** ")
              .append("required: ")
              .append(parameter.getRequired())
              .append(System.lineSeparator());
        }
      }
    }
  }

  private static Optional<Route> nonnullOp(String method, String pattern, Operation operation) {
    if (operation != null) {
      return Optional.of(new Route(method, pattern, operation));
    }
    return Optional.empty();
  }

  private static void operation(
      String method, String pattern, Operation operation, StringBuilder out) {
    if (operation != null) {
      out.append("=== ")
          .append(method)
          .append(" ")
          .append(pattern)
          .append(System.lineSeparator())
          .append(System.lineSeparator());
      if (operation.getSummary() != null) {
        out.append(operation.getSummary()).append(System.lineSeparator());
      }
      if (operation.getDescription() != null) {
        out.append(operation.getDescription()).append(System.lineSeparator());
      }
    }
  }

  private static void intro(OpenAPI openApi, StringBuilder out) {
    var info = openApi.getInfo();
    var title = info.getTitle();
    var version = info.getVersion();

    out.append("= ").append(title).append(System.lineSeparator());
    out.append("v")
        .append(version)
        .append(", ")
        .append(Instant.now())
        .append(System.lineSeparator());
    out.append(System.lineSeparator());
    withProperty(info, Info::getSummary, value -> out.append(value).append(System.lineSeparator()));
    withProperty(
        info, Info::getDescription, value -> out.append(value).append(System.lineSeparator()));
    withProperty(
        info,
        Info::getTermsOfService,
        value ->
            out.append(System.lineSeparator())
                .append("=== Term of Service")
                .append(System.lineSeparator())
                .append(value)
                .append(System.lineSeparator()));
    withProperty(
        info,
        Info::getContact,
        value -> {
          out.append(System.lineSeparator()).append("=== Contact").append(System.lineSeparator());
          var contact = new StringBuilder();
          withProperty(
              value,
              Contact::getName,
              name -> {
                withProperty(value, Contact::getUrl, contact::append);
                if (contact.isEmpty()) {
                  contact.append(name);
                } else {
                  contact.append("[").append(name).append("]");
                }
                withProperty(
                    value,
                    Contact::getEmail,
                    email ->
                        contact
                            .append(". ")
                            .append("mailto:")
                            .append(email)
                            .append("[Contact Support]"));
                contact.append(System.lineSeparator());
              });
          out.append(contact).append(System.lineSeparator());
        });
  }

  private static <S, T> void withProperty(S node, Function<S, T> mapper, Consumer<T> consumer) {
    var value = mapper.apply(node);
    if (value != null) {
      consumer.accept(value);
    }
    ;
  }

  private static void walkTree(
      List<String> path, JsonNode node, BiConsumer<List<String>, String> consumer) {
    if (node instanceof ObjectNode object) {
      object
          .fields()
          .forEachRemaining(
              e ->
                  walkTree(
                      Stream.concat(path.stream(), Stream.of(e.getKey())).toList(),
                      e.getValue(),
                      consumer));
    } else if (node.isArray()) {
      node.elements().forEachRemaining(e -> walkTree(path, e, consumer));
    } else if (node.isValueNode() && !node.isNull()) {
      consumer.accept(path, node.asText());
    }
  }
}
