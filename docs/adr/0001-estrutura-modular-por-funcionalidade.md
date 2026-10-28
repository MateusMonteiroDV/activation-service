# ADR 0001: Estrutura Modular por Funcionalidade (Package-by-Feature)

## Status
Aceito

## Contexto
O desafio exige clareza, manutenibilidade, separação de responsabilidades e evitar o uso de padrões desnecessários (*overengineering*). Frequentemente adota-se arquitetura hexagonal completa com portas e adaptadores para entidades simples, o que resulta em duplicação de classes e DTOs sem real benefício para domínios coesos.

## Decisão
Adotamos organização de pacotes por funcionalidade (*package-by-feature*):
- `com.deroyque.activation.request`: encapsula o ciclo de vida da solicitação (API, Application e Domain).
- `com.deroyque.activation.provider`: encapsula o cliente HTTP de integração com o provedor externo e seus tipos de retorno selados (`ProviderResult`).
- `com.deroyque.activation.shared`: transversais reutilizáveis (segurança, tratamento global de erros RFC 9457, correlação via MDC, relógio injetável).

A entidade JPA é mantida no subpacote `domain` contendo os métodos de transição de estado protegidos (sem setters livres), garantindo encapsulamento e testabilidade pura com JUnit sem subir o contexto Spring.

## Consequências
- Código coeso e de fácil navegação para desenvolvedores e revisores.
- Regras de domínio isoladas e verificadas via ArchUnit.
- Redução significativa de mapeadores triviais mantendo separação estrita entre DTOs de entrada/saída e modelo persistido.
