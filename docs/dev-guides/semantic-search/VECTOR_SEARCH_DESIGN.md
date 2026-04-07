# DataHub Vector Search Design Document

DataHub의 벡터 검색(Semantic Search) 기능에 대한 상세 설계 문서입니다.
시퀀스 다이어그램, 모듈 다이어그램, 컴포넌트 다이어그램 등의 UML을 포함합니다.

> **Note**: 다이어그램은 [Mermaid](https://mermaid.js.org/) 문법으로 작성되었습니다.
> GitHub, GitLab, Notion 등에서 자동 렌더링됩니다.

---

## Table of Contents

- [1. 개요](#1-개요)
- [2. 컴포넌트 다이어그램](#2-컴포넌트-다이어그램)
- [3. 모듈 다이어그램](#3-모듈-다이어그램)
- [4. 데이터 모델 (클래스 다이어그램)](#4-데이터-모델-클래스-다이어그램)
- [5. 임베딩 생성 시퀀스 다이어그램](#5-임베딩-생성-시퀀스-다이어그램)
- [6. 검색 시퀀스 다이어그램](#6-검색-시퀀스-다이어그램)
- [7. 벡터화 대상 필드](#7-벡터화-대상-필드)
- [8. OpenSearch 인덱스 매핑](#8-opensearch-인덱스-매핑)
- [9. 상태 다이어그램](#9-상태-다이어그램)
- [10. 배포 다이어그램](#10-배포-다이어그램)
- [11. 설정 참조](#11-설정-참조)

---

## 1. 개요

DataHub의 벡터 검색은 기존 키워드 검색에 **시맨틱 유사도 검색**을 추가하는 기능입니다.

- **임베딩 모델**: `nlpai-lab/KURE-v1` (768차원, 한국어 지원)
- **검색 엔진**: OpenSearch k-NN plugin (FAISS/HNSW)
- **인덱스 전략**: Dual-Index (기존 `_v2` + 시맨틱 `_v2_semantic`)
- **병합 전략**: 텍스트 검색 결과 우선, KNN-only 결과를 후미에 추가

---

## 2. 컴포넌트 다이어그램

시스템 전체의 컴포넌트 간 관계를 보여줍니다.

```mermaid
graph TB
    subgraph "Client Layer"
        GQL_CLIENT["GraphQL Client<br/>(Frontend / API)"]
    end

    subgraph "API Layer"
        RESOLVER["SearchAcrossEntitiesResolver<br/><i>datahub-graphql-core</i>"]
    end

    subgraph "Search Service Layer"
        ENTITY_CLIENT["EntityClient<br/>(Text Search)"]
        SEMANTIC_SEARCH["SemanticEntitySearchService<br/>(KNN Search)"]
    end

    subgraph "Embedding Provider Layer"
        EMB_FACTORY["EmbeddingProviderFactory"]
        EMB_HTTP["HttpEmbeddingProvider"]
        EMB_OPENAI["OpenAIEmbeddingProvider"]
        EMB_BEDROCK["AwsBedrockEmbeddingProvider"]
        EMB_COHERE["CohereEmbeddingProvider"]
        EMB_NOOP["NoOpEmbeddingProvider"]
    end

    subgraph "Event Processing Layer"
        MAE_CONSUMER["MAE Consumer"]
        EMBEDDING_HOOK["DatasetEmbeddingHook"]
    end

    subgraph "Storage Layer"
        OS_KEYWORD["OpenSearch<br/>datasetindex_v2<br/>(Keyword Index)"]
        OS_SEMANTIC["OpenSearch<br/>datasetindex_v2_semantic<br/>(Semantic Index)"]
        KAFKA["Kafka<br/>(MCL Topic)"]
        GMS_DB["GMS<br/>(MySQL / Postgres)"]
    end

    subgraph "External Service"
        EMB_SERVICE["Embedding Service<br/>(HTTP, port 8766)<br/>nlpai-lab/KURE-v1"]
    end

    GQL_CLIENT -->|"searchAcrossEntities"| RESOLVER
    RESOLVER -->|"Text Search"| ENTITY_CLIENT
    RESOLVER -->|"KNN Search"| SEMANTIC_SEARCH

    ENTITY_CLIENT --> OS_KEYWORD
    SEMANTIC_SEARCH -->|"query embedding"| EMB_FACTORY
    SEMANTIC_SEARCH -->|"nested kNN query"| OS_SEMANTIC

    EMB_FACTORY --> EMB_HTTP
    EMB_FACTORY --> EMB_OPENAI
    EMB_FACTORY --> EMB_BEDROCK
    EMB_FACTORY --> EMB_COHERE
    EMB_FACTORY --> EMB_NOOP

    EMB_HTTP -->|"POST /embed"| EMB_SERVICE

    GMS_DB -->|"MCL event"| KAFKA
    KAFKA --> MAE_CONSUMER
    MAE_CONSUMER --> EMBEDDING_HOOK
    EMBEDDING_HOOK -->|"document embedding"| EMB_FACTORY
    EMBEDDING_HOOK -->|"MCP (semanticContent)"| GMS_DB
    GMS_DB -->|"index write"| OS_SEMANTIC

    style RESOLVER fill:#4a9eff,color:#fff
    style SEMANTIC_SEARCH fill:#4a9eff,color:#fff
    style EMBEDDING_HOOK fill:#ff9f43,color:#fff
    style OS_SEMANTIC fill:#2ed573,color:#fff
    style EMB_SERVICE fill:#a55eea,color:#fff
```

---

## 3. 모듈 다이어그램

소스 코드 모듈과 주요 클래스의 소속 관계를 보여줍니다.

```mermaid
graph LR
    subgraph "datahub-graphql-core"
        R1["SearchAcrossEntitiesResolver"]
    end

    subgraph "metadata-io"
        S1["SemanticEntitySearchService"]
        S2["V2SemanticSearchMappingsBuilder"]
        E1["EmbeddingProvider &laquo;interface&raquo;"]
        E2["HttpEmbeddingProvider"]
        E3["OpenAIEmbeddingProvider"]
        E4["AwsBedrockEmbeddingProvider"]
        E5["CohereEmbeddingProvider"]
        E6["NoOpEmbeddingProvider"]
        U1["ESUtils"]
        U2["SearchResultUtils"]
    end

    subgraph "metadata-service/factories"
        F1["EmbeddingProviderFactory"]
        F2["SemanticSearchServiceFactory"]
    end

    subgraph "metadata-jobs/mae-consumer"
        H1["DatasetEmbeddingHook"]
    end

    subgraph "metadata-models (PDL)"
        M1["SemanticContent"]
        M2["EmbeddingModelData"]
        M3["EmbeddingChunk"]
    end

    subgraph "metadata-service/configuration"
        C1["application.yaml"]
        C2["SemanticSearchConfiguration"]
        C3["ModelEmbeddingConfig"]
        C4["EmbeddingProviderConfiguration"]
    end

    R1 -->|uses| S1
    S1 -->|uses| E1
    S1 -->|uses| U1
    S1 -->|uses| U2
    H1 -->|uses| E1
    H1 -->|produces| M1
    M1 -->|contains| M2
    M2 -->|contains| M3
    F1 -->|creates| E2
    F1 -->|creates| E3
    F1 -->|creates| E4
    F1 -->|creates| E5
    F1 -->|creates| E6
    F1 -->|reads| C4
    F2 -->|creates| S1
    S2 -->|reads| C2
    C2 -->|contains| C3
    E2 -.->|implements| E1
    E3 -.->|implements| E1
    E4 -.->|implements| E1
    E5 -.->|implements| E1
    E6 -.->|implements| E1
```

### 주요 파일 경로

| 컴포넌트                        | 파일 경로                                                                                                 |
| ------------------------------- | --------------------------------------------------------------------------------------------------------- |
| SearchAcrossEntitiesResolver    | `datahub-graphql-core/src/main/java/.../resolvers/search/SearchAcrossEntitiesResolver.java`               |
| SemanticEntitySearchService     | `metadata-io/src/main/java/.../search/semantic/SemanticEntitySearchService.java`                          |
| V2SemanticSearchMappingsBuilder | `metadata-io/src/main/java/.../search/elasticsearch/index/entity/v2/V2SemanticSearchMappingsBuilder.java` |
| DatasetEmbeddingHook            | `metadata-jobs/mae-consumer/src/main/java/.../kafka/hook/DatasetEmbeddingHook.java`                       |
| EmbeddingProviderFactory        | `metadata-service/factories/src/main/java/.../factory/search/semantic/EmbeddingProviderFactory.java`      |
| HttpEmbeddingProvider           | `metadata-io/src/main/java/.../search/embedding/HttpEmbeddingProvider.java`                               |
| SemanticContent (PDL)           | `metadata-models/src/main/pegasus/com/linkedin/common/SemanticContent.pdl`                                |
| EmbeddingModelData (PDL)        | `metadata-models/src/main/pegasus/com/linkedin/common/EmbeddingModelData.pdl`                             |
| EmbeddingChunk (PDL)            | `metadata-models/src/main/pegasus/com/linkedin/common/EmbeddingChunk.pdl`                                 |

---

## 4. 데이터 모델 (클래스 다이어그램)

### 4.1 PDL Aspect 스키마

```mermaid
classDiagram
    class SemanticContent {
        <<Aspect>>
        +embeddings: Map~string, EmbeddingModelData~
    }

    class EmbeddingModelData {
        +modelVersion: string
        +generatedAt: long
        +chunkingStrategy: string?
        +totalChunks: int
        +totalTokens: int?
        +chunks: EmbeddingChunk[]
    }

    class EmbeddingChunk {
        +position: int
        +vector: float[]
        +characterOffset: int?
        +characterLength: int?
        +tokenCount: int?
        +text: string?
    }

    SemanticContent "1" --> "*" EmbeddingModelData : embeddings map
    EmbeddingModelData "1" --> "*" EmbeddingChunk : chunks
```

### 4.2 Embedding Provider 계층 구조

```mermaid
classDiagram
    class EmbeddingProvider {
        <<interface>>
        +embed(text: String, model: String?): float[]
    }

    class HttpEmbeddingProvider {
        -baseUrl: String
        -timeout: Duration
        -httpClient: HttpClient
        +embed(text, model): float[]
        -doEmbed(text): float[]
        -parseEmbedding(body, url): float[]
    }

    class OpenAIEmbeddingProvider {
        -apiKey: String
        -endpoint: String
        -model: String
        +embed(text, model): float[]
    }

    class AwsBedrockEmbeddingProvider {
        -awsRegion: String
        -model: String
        -maxCharLength: int
        +embed(text, model): float[]
    }

    class CohereEmbeddingProvider {
        -apiKey: String
        -endpoint: String
        -model: String
        +embed(text, model): float[]
    }

    class NoOpEmbeddingProvider {
        +embed(text, model): float[]
    }

    EmbeddingProvider <|.. HttpEmbeddingProvider
    EmbeddingProvider <|.. OpenAIEmbeddingProvider
    EmbeddingProvider <|.. AwsBedrockEmbeddingProvider
    EmbeddingProvider <|.. CohereEmbeddingProvider
    EmbeddingProvider <|.. NoOpEmbeddingProvider

    class EmbeddingProviderFactory {
        +getInstance(): EmbeddingProvider
        -createLocalHttpProvider(config): EmbeddingProvider
        -createOpenAIProvider(config): EmbeddingProvider
        -createAwsBedrockProvider(config): EmbeddingProvider
        -createCohereProvider(config): EmbeddingProvider
    }

    EmbeddingProviderFactory ..> EmbeddingProvider : creates
```

### 4.3 OpenSearch 인덱스 매핑 구조

```mermaid
classDiagram
    class V2SemanticSearchMappingsBuilder {
        -v2MappingsBuilder: MappingsBuilder
        -semanticConfig: SemanticSearchConfiguration
        -indexConvention: IndexConvention
        +getIndexMappings(opContext): Collection~IndexMapping~
        -buildEmbeddingFieldConfig(): Map
        -addSemanticMappings(base): Collection~IndexMapping~
    }

    class SemanticSearchConfiguration {
        +enabled: boolean
        +enabledEntities: Set~String~
        +models: Map~String, ModelEmbeddingConfig~
        +embeddingProvider: EmbeddingProviderConfiguration
    }

    class ModelEmbeddingConfig {
        +vectorDimension: int
        +knnEngine: String
        +spaceType: String
        +efConstruction: int
        +m: int
    }

    V2SemanticSearchMappingsBuilder --> SemanticSearchConfiguration
    SemanticSearchConfiguration --> "*" ModelEmbeddingConfig : models
```

---

## 5. 임베딩 생성 시퀀스 다이어그램

Dataset 엔티티의 메타데이터가 변경될 때 임베딩이 자동 생성되는 전체 흐름입니다.

```mermaid
sequenceDiagram
    autonumber
    participant Source as Data Source<br/>(Ingestion)
    participant GMS as GMS Server
    participant Kafka as Kafka<br/>(MCL Topic)
    participant MAE as MAE Consumer
    participant Hook as DatasetEmbeddingHook
    participant EmbSvc as Embedding Service<br/>(KURE-v1, :8766)
    participant OS as OpenSearch

    Note over Source,OS: Phase 1: 메타데이터 변경 이벤트 발생

    Source->>GMS: MCP: UPSERT datasetProperties<br/>또는 schemaMetadata
    GMS->>GMS: Persist aspect to DB
    GMS->>Kafka: Publish MCL event<br/>(entityType=dataset,<br/>aspectName=datasetProperties)
    GMS->>OS: Index to datasetindex_v2<br/>(keyword index)

    Note over Source,OS: Phase 2: 임베딩 생성 (비동기)

    Kafka->>MAE: Consume MCL event
    MAE->>Hook: invoke(MetadataChangeLog)

    Hook->>Hook: isEligibleForProcessing()?<br/>- entityType == dataset?<br/>- changeType != DELETE?<br/>- aspect == datasetProperties<br/>  || schemaMetadata?

    alt Not eligible
        Hook-->>MAE: return (skip)
    end

    Hook->>GMS: getV2(urn, {datasetProperties})
    GMS-->>Hook: DatasetProperties<br/>(name, description)

    Hook->>GMS: getV2(urn, {schemaMetadata})
    GMS-->>Hook: SchemaMetadata<br/>(field paths)

    Hook->>Hook: buildEmbeddingText()<br/>"테이블명\n설명\n컬럼1 컬럼2 ..."<br/>(max 2048 chars)

    alt Empty text
        Hook-->>MAE: return (skip)
    end

    Hook->>EmbSvc: POST /embed<br/>{"texts": ["embedding text"]}
    EmbSvc-->>Hook: {"embeddings": [[0.1, 0.2, ...]]}<br/>(768-dim float[])

    Hook->>Hook: buildSemanticContent()<br/>SemanticContent {<br/>  embeddings: {<br/>    "kure_v1": {<br/>      modelVersion: "local/nlpai-lab/KURE-v1",<br/>      chunks: [{position:0, vector:[...]}]<br/>    }<br/>  }<br/>}

    Note over Source,OS: Phase 3: 임베딩 저장

    Hook->>GMS: ingestProposal(MCP)<br/>entityType=dataset<br/>aspectName=semanticContent<br/>changeType=UPSERT
    GMS->>GMS: Persist semanticContent to DB
    GMS->>Kafka: Publish MCL for semanticContent
    GMS->>OS: Index to datasetindex_v2_semantic<br/>(embeddings.kure_v1.chunks[].vector)

    Hook-->>MAE: complete (success logged)
```

### 임베딩 텍스트 구성 상세

```mermaid
graph TD
    A["Dataset 메타데이터 수집"] --> B["DatasetProperties 조회"]
    A --> C["SchemaMetadata 조회"]

    B --> D["name: 'user_activity_log'"]
    B --> E["description: '사용자 활동 로그 테이블'"]
    C --> F["fields: [user_id, action, timestamp, ip_address]"]

    D --> G["buildEmbeddingText()"]
    E --> G
    F --> G

    G --> H["결합 텍스트:<br/>'user_activity_log<br/>사용자 활동 로그 테이블<br/>user_id action timestamp ip_address'"]

    H --> I{"length > 2048?"}
    I -->|Yes| J["truncate to 2048 chars"]
    I -->|No| K["그대로 사용"]

    J --> L["EmbeddingProvider.embed()"]
    K --> L

    L --> M["float[768] 벡터"]

    style G fill:#4a9eff,color:#fff
    style L fill:#a55eea,color:#fff
    style M fill:#2ed573,color:#fff
```

---

## 6. 검색 시퀀스 다이어그램

### 6.1 전체 검색 흐름 (Text + KNN 병합)

```mermaid
sequenceDiagram
    autonumber
    participant Client as GraphQL Client
    participant Resolver as SearchAcrossEntities<br/>Resolver
    participant EC as EntityClient<br/>(Text Search)
    participant SES as SemanticEntity<br/>SearchService
    participant EmbProv as EmbeddingProvider
    participant EmbSvc as Embedding Service<br/>(KURE-v1)
    participant OS_KW as OpenSearch<br/>(datasetindex_v2)
    participant OS_SEM as OpenSearch<br/>(datasetindex_v2_semantic)

    Client->>Resolver: searchAcrossEntities(input)<br/>query: "사용자 활동 데이터"<br/>types: [DATASET]

    Resolver->>Resolver: Resolve view, filters,<br/>entity types, search flags

    Note over Resolver,OS_SEM: Text Search 실행

    Resolver->>EC: searchAcrossEntities(<br/>entities, query, filter,<br/>start, count)
    EC->>OS_KW: Standard text query<br/>(BM25 scoring)
    OS_KW-->>EC: Text search results
    EC-->>Resolver: SearchResult (text)

    Note over Resolver,OS_SEM: KNN Search 실행 (knnSearchEnabled == true)

    alt KNN search enabled
        Resolver->>SES: search(opContext, entityNames,<br/>query, filter, sort, 0, count)

        SES->>SES: Map entity names to<br/>semantic index names<br/>"datasetindex_v2_semantic"

        SES->>EmbProv: embed("사용자 활동 데이터", null)
        EmbProv->>EmbSvc: POST /embed<br/>{"texts": ["사용자 활동 데이터"]}
        EmbSvc-->>EmbProv: {"embeddings": [[...]]}
        EmbProv-->>SES: float[768]

        SES->>SES: Build nested kNN query<br/>with pre-filtering<br/>k = min(500, ceil((from+pageSize)*1.2))

        SES->>OS_SEM: POST /datasetindex_v2_semantic/_search<br/>{nested: {path: "embeddings.kure_v1.chunks",<br/>query: {knn: {vector: [...], k: N}}}}
        OS_SEM-->>SES: KNN results (scored by cosine similarity)

        SES->>SES: Extract URNs, scores,<br/>features, extraFields

        SES-->>Resolver: SearchResult (knn)
    end

    Note over Resolver,OS_SEM: 결과 병합

    Resolver->>Resolver: mergeWithKnnResults()<br/>1. Text results (순서 유지)<br/>2. KNN-only results 추가<br/>   (중복 URN 제거)

    Resolver-->>Client: SearchResults<br/>(merged text + knn)
```

### 6.2 결과 병합 로직 상세

```mermaid
flowchart TD
    A["Text Search Results<br/>[A, B, C, D]"] --> E["Build URN set<br/>{A, B, C, D}"]
    B2["KNN Search Results<br/>[B, E, F, C]"] --> F["Filter: URN not in text set"]

    F --> G["KNN-only entities<br/>[E, F]"]

    E --> H["Merge"]
    G --> H

    H --> I["Final Results<br/>[A, B, C, D, E, F]<br/>text results first,<br/>KNN-only appended"]

    style A fill:#4a9eff,color:#fff
    style B2 fill:#2ed573,color:#fff
    style I fill:#ff9f43,color:#fff
```

### 6.3 KNN 쿼리 실행 상세

```mermaid
sequenceDiagram
    participant SES as SemanticEntitySearchService
    participant OS as OpenSearch (Low Level REST Client)

    SES->>SES: Calculate k<br/>k = min(500, ceil((from + pageSize) * 1.2))

    SES->>SES: Build filter map (ESUtils)<br/>Transform _entityType → _index filters<br/>Apply SemanticIndexConvention

    SES->>SES: buildSemanticQueryWithPreFiltering()

    Note right of SES: Query Structure:<br/>{<br/>  "size": k,<br/>  "track_total_hits": false,<br/>  "_source": ["urn", "browsePaths", ...],<br/>  "query": {<br/>    "nested": {<br/>      "path": "embeddings.kure_v1.chunks",<br/>      "score_mode": "max",<br/>      "query": {<br/>        "knn": {<br/>          "embeddings.kure_v1.chunks.vector": {<br/>            "vector": [0.1, 0.2, ...],<br/>            "k": 500,<br/>            "filter": { ... }<br/>          }<br/>        }<br/>      }<br/>    }<br/>  }<br/>}

    SES->>OS: POST /{indices}/_search<br/>(ignore_unavailable=true,<br/>allow_no_indices=true)

    OS-->>SES: Response JSON

    SES->>SES: Parse hits:<br/>- Extract URN from _source.urn<br/>- Set score from _score<br/>- Build features (SEARCH_BACKEND_SCORE)<br/>- Build extraFields from _source

    SES->>SES: Slice [from, from+pageSize)

    SES-->>SES: return List~SearchEntity~
```

---

## 7. 벡터화 대상 필드

현재 구현에서는 **Dataset** 엔티티만 벡터화를 지원합니다.

```mermaid
graph LR
    subgraph "Dataset Entity"
        DP["datasetProperties aspect"]
        SM["schemaMetadata aspect"]
    end

    subgraph "벡터화 입력 필드"
        NAME["name<br/>(테이블명)"]
        DESC["description<br/>(테이블 설명)"]
        COLS["fields[*].fieldPath<br/>(컬럼명 목록)"]
    end

    subgraph "출력"
        VEC["float[768] vector<br/>(KURE-v1 embedding)"]
    end

    DP --> NAME
    DP --> DESC
    SM --> COLS

    NAME --> CONCAT["buildEmbeddingText()<br/>'name\\ndescription\\ncol1 col2 ...'"]
    DESC --> CONCAT
    COLS --> CONCAT

    CONCAT --> VEC

    style VEC fill:#2ed573,color:#fff
    style CONCAT fill:#4a9eff,color:#fff
```

| 소스 Aspect         | 필드                  | 설명                             | 예시                                  |
| ------------------- | --------------------- | -------------------------------- | ------------------------------------- |
| `datasetProperties` | `name`                | Dataset 이름                     | `user_activity_log`                   |
| `datasetProperties` | `description`         | Dataset 설명                     | `사용자 활동을 기록하는 테이블`       |
| `schemaMetadata`    | `fields[*].fieldPath` | 컬럼 경로 목록 (space-separated) | `user_id action timestamp ip_address` |

### 트리거 조건

- `datasetProperties` aspect UPSERT 시
- `schemaMetadata` aspect UPSERT 시
- DELETE 이벤트는 무시
- `semanticContent` aspect 변경은 무시 (무한 루프 방지)

---

## 8. OpenSearch 인덱스 매핑

### 8.1 Dual-Index 구조

```mermaid
graph TB
    subgraph "Keyword Index (기존)"
        KW["datasetindex_v2"]
        KW_FIELDS["urn: keyword<br/>browsePaths: text<br/>title: text<br/>description: text<br/>tags: keyword<br/>...표준 검색 필드들"]
    end

    subgraph "Semantic Index (추가)"
        SEM["datasetindex_v2_semantic"]
        SEM_FIELDS["urn: keyword<br/>browsePaths: text<br/>title: text<br/>description: text<br/>tags: keyword<br/>...표준 검색 필드들<br/>─────────────────<br/><b>+ embeddings (추가 필드)</b>"]
    end

    KW --> KW_FIELDS
    SEM --> SEM_FIELDS

    style SEM fill:#2ed573,color:#fff
```

### 8.2 embeddings 필드 상세 매핑

```mermaid
graph TD
    EMB["embeddings<br/><i>(object)</i>"]
    MODEL["kure_v1<br/><i>(object)</i>"]
    MV["modelVersion<br/><i>type: keyword</i>"]
    GA["generatedAt<br/><i>type: date</i>"]
    TC["totalChunks<br/><i>type: integer</i>"]
    CHUNKS["chunks<br/><i>type: nested</i>"]
    VEC["vector<br/><i>type: knn_vector</i><br/>dimension: 768<br/>method: hnsw<br/>engine: faiss<br/>space_type: cosinesimil<br/>ef_construction: 128<br/>m: 16"]
    TXT["text<br/><i>type: text</i><br/>index: false"]
    POS["position<br/><i>type: integer</i>"]
    COFF["characterOffset<br/><i>type: integer</i>"]
    CLEN["characterLength<br/><i>type: integer</i>"]
    TCNT["tokenCount<br/><i>type: integer</i>"]

    EMB --> MODEL
    MODEL --> MV
    MODEL --> GA
    MODEL --> TC
    MODEL --> CHUNKS
    CHUNKS --> VEC
    CHUNKS --> TXT
    CHUNKS --> POS
    CHUNKS --> COFF
    CHUNKS --> CLEN
    CHUNKS --> TCNT

    style VEC fill:#ff6b6b,color:#fff
    style EMB fill:#4a9eff,color:#fff
    style CHUNKS fill:#ffa502,color:#fff
```

### 8.3 벡터 필드 전체 경로

```
embeddings.kure_v1.chunks[*].vector
```

| 속성                                  | 값                                          |
| ------------------------------------- | ------------------------------------------- |
| **type**                              | `knn_vector`                                |
| **dimension**                         | 768                                         |
| **method.name**                       | `hnsw` (Hierarchical Navigable Small World) |
| **method.engine**                     | `faiss`                                     |
| **method.space_type**                 | `cosinesimil` (코사인 유사도)               |
| **method.parameters.ef_construction** | 128 (빌드 시간 정확도)                      |
| **method.parameters.m**               | 16 (노드 당 연결 수)                        |

### 8.4 실제 OpenSearch 도큐먼트 예시

```json
{
  "urn": "urn:li:dataset:(urn:li:dataPlatform:mysql,db.user_activity_log,PROD)",
  "browsePaths": "/mysql/db",
  "title": "user_activity_log",
  "description": "사용자 활동 로그 테이블",
  "embeddings": {
    "kure_v1": {
      "modelVersion": "local/nlpai-lab/KURE-v1",
      "generatedAt": 1712467200000,
      "totalChunks": 1,
      "chunks": [
        {
          "position": 0,
          "text": "user_activity_log\n사용자 활동 로그 테이블\nuser_id action timestamp ip_address",
          "vector": [0.023, -0.041, 0.087, ...],
          "tokenCount": 28
        }
      ]
    }
  }
}
```

---

## 9. 상태 다이어그램

### 9.1 임베딩 생성 Hook 상태

```mermaid
stateDiagram-v2
    [*] --> Idle: Hook initialized

    Idle --> CheckEligibility: MCL event received

    CheckEligibility --> Idle: Not eligible<br/>(wrong entity/aspect/changeType)
    CheckEligibility --> FetchMetadata: Eligible

    FetchMetadata --> BuildText: Properties + Schema fetched
    FetchMetadata --> Idle: Fetch failed (logged)

    BuildText --> Idle: Empty text (skip)
    BuildText --> Truncate: text.length > 2048
    BuildText --> GenerateEmbedding: text ready

    Truncate --> GenerateEmbedding: truncated to 2048

    GenerateEmbedding --> PersistAspect: float[] received
    GenerateEmbedding --> Idle: Provider error (logged, swallowed)

    PersistAspect --> Idle: MCP ingested successfully
    PersistAspect --> Idle: Ingest failed (logged, swallowed)
```

### 9.2 검색 결과 병합 상태

```mermaid
stateDiagram-v2
    [*] --> TextSearch: Query received

    TextSearch --> CheckKNN: Text results ready

    CheckKNN --> ReturnText: KNN disabled
    CheckKNN --> KNNSearch: KNN enabled

    KNNSearch --> MergeResults: KNN results ready
    KNNSearch --> ReturnText: KNN failed (fallback)

    MergeResults --> DeduplicateURNs: Build URN set from text
    DeduplicateURNs --> AppendKNNOnly: Filter KNN-only entities
    AppendKNNOnly --> ReturnMerged: Merged results

    ReturnText --> [*]
    ReturnMerged --> [*]
```

---

## 10. 배포 다이어그램

```mermaid
graph TB
    subgraph "Docker Compose Stack"
        subgraph "Frontend"
            FE["datahub-frontend-react<br/>:9002"]
        end

        subgraph "Backend Services"
            GMS["datahub-gms<br/>:8080"]
            MCE["datahub-mce-consumer"]
            MAE["datahub-mae-consumer<br/>(DatasetEmbeddingHook)"]
        end

        subgraph "Embedding Service"
            EMB["datahub-embedding-service<br/>:8766<br/>(nlpai-lab/KURE-v1)"]
        end

        subgraph "Infrastructure"
            KAFKA["Kafka<br/>:9092"]
            OS["OpenSearch<br/>:9200<br/>(k-NN plugin enabled)"]
            MYSQL["MySQL / Postgres<br/>:3306"]
        end
    end

    FE -->|"GraphQL"| GMS
    GMS -->|"MCL publish"| KAFKA
    GMS -->|"read/write"| MYSQL
    GMS -->|"index"| OS

    KAFKA -->|"consume MCL"| MAE
    MAE -->|"POST /embed"| EMB
    MAE -->|"MCP ingest"| GMS

    GMS -.->|"query embedding"| EMB
    GMS -.->|"kNN search"| OS

    style EMB fill:#a55eea,color:#fff
    style MAE fill:#ff9f43,color:#fff
    style OS fill:#2ed573,color:#fff
```

### 환경변수 설정

```
MAE Consumer / GMS:
  DATASET_EMBEDDING_ENABLED=true
  ELASTICSEARCH_SEMANTIC_SEARCH_ENABLED=true
  ELASTICSEARCH_SEMANTIC_SEARCH_ENTITIES=dataset
  EMBEDDING_PROVIDER_TYPE=local-http
  EMBEDDING_SERVICE_URL=http://datahub-embedding-service:8766
  SEARCH_SERVICE_SEMANTIC_SEARCH_ENABLED=true
```

---

## 11. 설정 참조

### application.yaml 주요 설정

```yaml
# OpenSearch 시맨틱 인덱스 설정
elasticsearch:
  entityIndex:
    semanticSearch:
      enabled: ${ELASTICSEARCH_SEMANTIC_SEARCH_ENABLED:false}
      enabledEntities: ${ELASTICSEARCH_SEMANTIC_SEARCH_ENTITIES:document}
      models:
        kure_v1:
          vectorDimension: 768
          knnEngine: faiss
          spaceType: cosinesimil
          efConstruction: 128
          m: 16
      embeddingProvider:
        type: ${EMBEDDING_PROVIDER_TYPE:local-http}
        localHttp:
          baseUrl: ${EMBEDDING_SERVICE_URL:http://localhost:8766}
          timeoutSeconds: ${EMBEDDING_SERVICE_TIMEOUT:120}

# 검색 서비스 시맨틱 검색 활성화
search:
  semanticSearchEnabled: ${SEARCH_SERVICE_SEMANTIC_SEARCH_ENABLED:false}

# MAE Hook 활성화
featureFlags:
  datasetEmbeddingEnabled: ${DATASET_EMBEDDING_ENABLED:false}
```

### 활성화 체크리스트

1. Embedding Service 컨테이너 실행 (`:8766`)
2. `ELASTICSEARCH_SEMANTIC_SEARCH_ENABLED=true` (시맨틱 인덱스 생성)
3. `ELASTICSEARCH_SEMANTIC_SEARCH_ENTITIES=dataset` (대상 엔티티)
4. `DATASET_EMBEDDING_ENABLED=true` (MAE Hook 활성화)
5. `SEARCH_SERVICE_SEMANTIC_SEARCH_ENABLED=true` (검색 시 KNN 사용)
6. `EMBEDDING_PROVIDER_TYPE=local-http` (임베딩 프로바이더)
7. `EMBEDDING_SERVICE_URL=http://datahub-embedding-service:8766`
