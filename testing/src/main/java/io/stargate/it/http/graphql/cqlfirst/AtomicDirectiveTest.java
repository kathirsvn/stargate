package io.stargate.it.http.graphql.cqlfirst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.apollographql.apollo.ApolloClient;
import com.apollographql.apollo.api.Error;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.example.graphql.client.betterbotz.atomic.InsertOrdersWithAtomicMutation;
import com.example.graphql.client.betterbotz.atomic.ProductsAndOrdersMutation;
import com.example.graphql.client.betterbotz.type.MutationConsistency;
import com.example.graphql.client.betterbotz.type.MutationOptions;
import com.example.graphql.client.betterbotz.type.OrdersInput;
import com.example.graphql.client.betterbotz.type.ProductsInput;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TODO refactor this to use {@link CqlFirstClient} instead of extending {@link ApolloTestBase}. */
public class AtomicDirectiveTest extends ApolloTestBase {
  @Test
  @DisplayName("Should execute single mutation with atomic directive")
  public void shouldSupportSingleMutationWithAtomicDirective() {
    UUID id = UUID.randomUUID();
    String productName = "prod " + id;
    String description = "desc " + id;
    String customer = "cust 1";

    ApolloClient client = getApolloClient("/graphql/" + keyspace);
    InsertOrdersWithAtomicMutation mutation =
        InsertOrdersWithAtomicMutation.builder()
            .value(
                OrdersInput.builder()
                    .prodName(productName)
                    .customerName(customer)
                    .price("456")
                    .description(description)
                    .build())
            .build();

    getObservable(client.mutate(mutation));

    assertThat(
            session
                .execute(
                    SimpleStatement.newInstance(
                        "SELECT * FROM \"Orders\" WHERE \"prodName\" = ?", productName))
                .one())
        .isNotNull()
        .extracting(r -> r.getString("\"customerName\""), r -> r.getString("description"))
        .containsExactly(customer, description);
  }

  @Test
  @DisplayName(
      "When invalid, multiple mutations with atomic directive should return error response")
  public void multipleMutationsWithAtomicDirectiveShouldReturnErrorResponse() {
    ApolloClient client = getApolloClient("/graphql/" + keyspace);
    ProductsAndOrdersMutation mutation =
        ProductsAndOrdersMutation.builder()
            .productValue(
                // The mutation is invalid as parts of the primary key are missing
                ProductsInput.builder()
                    .id(UUID.randomUUID().toString())
                    .prodName("prodName sample")
                    .customerName("customer name")
                    .build())
            .orderValue(
                OrdersInput.builder().prodName("a").customerName("b").description("c").build())
            .build();

    GraphQLTestException ex =
        catchThrowableOfType(
            () -> getObservable(client.mutate(mutation)), GraphQLTestException.class);

    assertThat(ex).isNotNull();
    assertThat(ex.errors)
        // One error per query
        .hasSize(2)
        .first()
        .extracting(Error::getMessage)
        .asString()
        .contains("Some clustering keys are missing");
  }

  @Test
  @DisplayName("Multiple options with atomic directive should return error response")
  public void multipleOptionsWithAtomicDirectiveShouldReturnErrorResponse() {
    ApolloClient client = getApolloClient("/graphql/" + keyspace);

    ProductsAndOrdersMutation mutation =
        ProductsAndOrdersMutation.builder()
            .productValue(
                ProductsInput.builder()
                    .id(Uuids.random().toString())
                    .prodName("prod 1")
                    .price("1")
                    .name("prod1")
                    .created(now())
                    .build())
            .orderValue(
                OrdersInput.builder()
                    .prodName("prod 1")
                    .customerName("cust 1")
                    .description("my description")
                    .build())
            .options(MutationOptions.builder().consistency(MutationConsistency.ALL).build())
            .build();

    GraphQLTestException ex =
        catchThrowableOfType(
            () -> getObservable(client.mutate(mutation)), GraphQLTestException.class);

    assertThat(ex).isNotNull();
    assertThat(ex.errors)
        // One error per query
        .hasSize(2)
        .first()
        .extracting(Error::getMessage)
        .asString()
        .contains("options can only de defined once in an @atomic mutation selection");
  }
}
