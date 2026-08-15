-- pgvector supplies the `vector` column type and the distance operators (<=>, <->, <#>)
-- used by Spring AI's PgVectorStore similarity search.
CREATE EXTENSION IF NOT EXISTS vector;
