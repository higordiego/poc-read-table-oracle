package com.example.projection.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI projectionPocOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Price Calculation API — Oracle Memoptimized Read Projection PoC")
                        .description("""
                                Calcula preço por cliente (segmento -> tabela de preço -> tarifa -> \
                                regras) e serve leituras através de uma projeção mantida de forma \
                                síncrona por trigger, em esquema blue/green. Endpoints administrativos \
                                (/api/admin/projection/*) não têm autenticação -- não expor fora de um \
                                ambiente controlado. Documentação completa em docs/ (ver docs/README.md \
                                no repositório).""")
                        .version("0.0.1-SNAPSHOT")
                        .contact(new Contact().name("Time responsável pela PoC")))
                .tags(List.of(
                        new Tag().name("Customers").description("Cadastro de clientes e seu segmento."),
                        new Tag().name("Calculations — write")
                                .description("Criação e atualização de cálculos de preço (caminho transacional)."),
                        new Tag().name("Calculations — read")
                                .description("Leitura por PK (elegível a Fast Lookup) e busca filtrada por keyset."),
                        new Tag().name("Projection Admin")
                                .description("Status, validação e rebuild blue/green da projeção -- sem autenticação, uso interno/operacional.")));
    }
}
