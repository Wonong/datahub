# Tutorial 1: Entity-Aspect 모델 이해하기

DataHub의 모든 메타데이터는 **Entity-Aspect 모델**로 구조화됩니다.
이 튜토리얼에서는 이 핵심 모델의 구조, 스키마 정의 방식, URN 체계를 직접 코드를 탐색하며 학습합니다.

---

## 학습 목표

이 튜토리얼을 마치면 다음을 할 수 있습니다:

1. Entity와 Aspect의 관계를 설명할 수 있다
2. `entity-registry.yml`을 읽고 엔티티의 구조를 파악할 수 있다
3. PDL 스키마를 읽고 Aspect의 필드와 어노테이션을 이해할 수 있다
4. URN의 구조와 역할을 이해할 수 있다
5. 스키마 변경이 시스템 전체에 미치는 영향을 설명할 수 있다

---

## 사전 지식

- Java, Spring 기본 지식 (이미 보유)
- 코드 에디터에서 DataHub 소스를 열어둔 상태

---

## 1. Entity란?

**Entity**는 DataHub에서 관리하는 메타데이터의 **대상 객체**입니다.

실세계에서 관리해야 할 데이터 자산을 떠올려보세요:

- MySQL 테이블 → `dataset` 엔티티
- Airflow DAG → `dataFlow` 엔티티
- Looker 대시보드 → `dashboard` 엔티티
- 회사 직원 → `corpUser` 엔티티

각 엔티티는 **URN(Uniform Resource Name)**이라는 고유 식별자를 가집니다.

### 실습 1-1: entity-registry.yml 열어보기

DataHub의 모든 엔티티 정의는 하나의 YAML 파일에 있습니다:

```
metadata-models/src/main/resources/entity-registry.yml
```

이 파일을 열고 `dataset` 엔티티 정의를 찾아보세요:

```yaml
- name: dataset
  doc: Datasets represent logical or physical data assets...
  category: core
  keyAspect: datasetKey
  searchGroup: primary
  aspects:
    - datasetProperties
    - schemaMetadata
    - ownership
    - globalTags
    # ... 30개 이상의 aspect
```

**관찰 포인트**:

| 필드          | 의미                                             | 예시                       |
| ------------- | ------------------------------------------------ | -------------------------- |
| `name`        | 엔티티 타입 이름                                 | `dataset`                  |
| `category`    | 분류 (`core` = 외부 사용, `internal` = 시스템용) | `core`                     |
| `keyAspect`   | 이 엔티티의 식별 키 aspect                       | `datasetKey`               |
| `searchGroup` | 검색 인덱스 그룹                                 | `primary`                  |
| `aspects`     | 이 엔티티가 가질 수 있는 aspect 목록             | `[datasetProperties, ...]` |

### 실습 1-2: 단순한 엔티티와 복잡한 엔티티 비교

`entity-registry.yml`에서 다음 두 엔티티를 비교해보세요:

**단순 엔티티** - `dataPlatform`:

```yaml
- name: dataPlatform
  category: core
  keyAspect: dataPlatformKey
  aspects:
    - dataPlatformInfo
```

`dataPlatform`은 aspect가 1개뿐입니다. MySQL, Kafka 같은 플랫폼을 나타냅니다.

**복잡 엔티티** - `dataset`:

```yaml
- name: dataset
  aspects:
    - datasetProperties
    - schemaMetadata
    - ownership
    - globalTags
    - glossaryTerms
    - upstreamLineage
    # ... 30개 이상
```

`dataset`은 30개 이상의 aspect를 가집니다. 다양한 메타데이터를 모듈별로 관리하기 때문입니다.

> **핵심 원리**: Aspect의 수는 해당 엔티티가 가질 수 있는 메타데이터의 다양성을 반영합니다.
> 새로운 종류의 메타데이터를 추가하고 싶으면 새 Aspect를 만들어 등록하면 됩니다.

---

## 2. Aspect란?

**Aspect**는 엔티티의 메타데이터를 구성하는 **모듈화된 단위**입니다.

하나의 엔티티에 모든 메타데이터를 넣는 대신, 관심사별로 분리합니다:

| Aspect              | 내용               | 독립적 업데이트? |
| ------------------- | ------------------ | :--------------: |
| `datasetProperties` | 이름, 설명, 생성일 |        O         |
| `schemaMetadata`    | 테이블 컬럼 정보   |        O         |
| `ownership`         | 소유자 목록        |        O         |
| `globalTags`        | 태그 목록          |        O         |
| `upstreamLineage`   | 상류 데이터 의존성 |        O         |

**왜 이렇게 분리하나?**

1. **독립적 업데이트**: 소유자를 바꿀 때 스키마 정보를 건드릴 필요 없음
2. **다양한 소스**: 태그는 사람이, 스키마는 크롤러가, 리니지는 파이프라인이 각각 제공
3. **선택적 조회**: 필요한 aspect만 가져올 수 있어 효율적
4. **버전 관리**: 각 aspect는 독립적으로 버전이 관리됨

### 실습 2-1: PDL 스키마 읽기

Aspect의 구조는 **PDL(Pegasus Data Language)** 파일로 정의됩니다. 다음 파일을 열어보세요:

```
metadata-models/src/main/pegasus/com/linkedin/dataset/DatasetProperties.pdl
```

핵심 부분을 살펴봅시다:

```pegasus
// 1. Aspect 어노테이션: 이 레코드가 aspect임을 선언
@Aspect = {
  "name": "datasetProperties"
}
record DatasetProperties includes CustomProperties, ExternalReference {

  // 2. @Searchable 어노테이션: 이 필드를 검색 인덱스에 포함
  @Searchable = {
    "fieldType": "WORD_GRAM",
    "enableAutocomplete": true,
    "boostScore": 10.0
  }
  name: optional string

  // 3. 일반 필드: 검색 인덱스에 포함되지 않음
  description: optional string

  // 4. 타임스탬프 필드: 중첩 경로를 가진 @Searchable
  @Searchable = {
    "/time": {
      "fieldName": "createdAt",
      "fieldType": "DATETIME"
    }
  }
  created: optional TimeStamp
}
```

**어노테이션 정리**:

| 어노테이션      | 위치        | 역할                                         |
| --------------- | ----------- | -------------------------------------------- |
| `@Aspect`       | 레코드 레벨 | 이 레코드가 Aspect임을 선언하고 이름 지정    |
| `@Searchable`   | 필드 레벨   | 이 필드를 OpenSearch 검색 인덱스에 자동 추가 |
| `@Relationship` | 필드 레벨   | 이 필드로 그래프 관계(리니지 등) 생성        |

### 실습 2-2: Key Aspect 살펴보기

모든 엔티티에는 `keyAspect`가 있습니다. 이것이 엔티티의 **URN을 결정**합니다.

```
metadata-models/src/main/pegasus/com/linkedin/metadata/key/DatasetKey.pdl
```

```pegasus
@Aspect = {
  "name": "datasetKey"
}
record DatasetKey {
  // 소속 플랫폼 (예: urn:li:dataPlatform:mysql)
  platform: DataPlatformUrn

  // 데이터셋 이름 (예: my_db.users)
  @Searchable = { ... }
  name: string

  // 패브릭/환경 (예: PROD, DEV)
  origin: FabricType
}
```

이 세 필드가 조합되어 URN이 만들어집니다:

```
urn:li:dataset:(urn:li:dataPlatform:mysql,my_db.users,PROD)
```

### 실습 2-3: @Relationship 어노테이션 찾기

리니지(데이터 흐름) 관계를 정의하는 aspect를 찾아보세요:

```
metadata-models/src/main/pegasus/com/linkedin/dataset/UpstreamLineage.pdl
```

`@Relationship` 어노테이션이 어떻게 사용되는지 확인하세요.
이 어노테이션이 붙은 필드는 그래프 DB(Neo4j 또는 ES Graph)에 관계로 저장됩니다.

---

## 3. URN (Uniform Resource Name)

### 3.1 URN 구조

모든 엔티티는 URN으로 고유하게 식별됩니다:

```
urn:li:<entityType>:<keyFields>
```

**예시**:

| URN                                                             | 엔티티 타입  | 키 필드                |
| --------------------------------------------------------------- | ------------ | ---------------------- |
| `urn:li:dataset:(urn:li:dataPlatform:mysql,db.users,PROD)`      | dataset      | platform, name, origin |
| `urn:li:corpuser:john.doe`                                      | corpUser     | username               |
| `urn:li:dashboard:(urn:li:dataPlatform:looker,dashboards.1234)` | dashboard    | platform, dashboardId  |
| `urn:li:dataPlatform:mysql`                                     | dataPlatform | platformName           |

### 3.2 URN 파싱

코드에서 URN을 다루는 유틸리티를 살펴보세요:

```
li-utils/src/main/javaPegasus/com/linkedin/common/urn/UrnUtils.java
```

주요 메서드:

- `UrnUtils.getUrn(String)`: 문자열 → Urn 객체
- `Urn.getEntityType()`: 엔티티 타입 추출
- `Urn.getId()`: 키 부분 추출

### 실습 3-1: URN 구조 분석

다음 URN에서 각 부분을 식별해보세요:

```
urn:li:dataset:(urn:li:dataPlatform:bigquery,project.schema.table_name,PROD)
```

- 엔티티 타입: ?
- 플랫폼: ?
- 데이터셋 이름: ?
- 환경: ?

---

## 4. 런타임 메타데이터: EntitySpec과 AspectSpec

PDL 스키마는 빌드 시 Java 코드로 변환되고, GMS 시작 시 `EntityRegistry`로 로드됩니다.

### 4.1 EntityRegistry

```
entity-registry/src/main/java/com/linkedin/metadata/models/registry/EntityRegistry.java
```

핵심 메서드:

- `getEntitySpec(String entityName)`: 엔티티 사양 조회
- `getAspectSpecs()`: 모든 aspect 사양 목록

### 4.2 EntitySpec

```
entity-registry/src/main/java/com/linkedin/metadata/models/EntitySpec.java
```

엔티티의 런타임 메타데이터를 담고 있습니다:

- 어떤 aspect를 가지는지
- 각 aspect에 어떤 검색 필드가 있는지
- 어떤 관계 필드가 있는지

### 4.3 AspectSpec

```
entity-registry/src/main/java/com/linkedin/metadata/models/AspectSpec.java
```

aspect의 런타임 메타데이터:

- `getSearchableFieldSpecs()`: @Searchable 필드 목록
- `getRelationshipFieldSpecs()`: @Relationship 필드 목록
- `getTimeseriesFieldSpecs()`: 타임시리즈 필드 목록

### 실습 4-1: 코드로 추적하기

다음 흐름을 코드에서 추적해보세요:

```
entity-registry.yml (YAML)
  → EntityRegistryFactory.java (파싱)
    → EntityRegistry (인터페이스)
      → EntitySpec (엔티티 메타)
        → AspectSpec (aspect 메타)
          → SearchableFieldSpec (@Searchable 정보)
```

Factory 파일 위치:

```
metadata-service/factories/src/main/java/com/linkedin/gms/factory/entityregistry/EntityRegistryFactory.java
```

---

## 5. 스키마 변경의 영향 범위

PDL 스키마를 변경하면 시스템 전체에 영향을 미칩니다:

```
PDL 스키마 수정
  ├─→ Java 코드 재생성 (빌드 시)
  ├─→ OpenSearch 인덱스 매핑 변경 (GMS 시작 시)
  ├─→ GraphQL 스키마 변경 (필요 시)
  ├─→ REST API 변경 (필요 시)
  └─→ Python SDK 변경 (필요 시)
```

> **중요**: `metadata-models/`를 수정하면 모든 컨테이너가 재빌드됩니다.
> 이 때문에 `metadata-models`의 변경은 신중하게 수행해야 합니다.

---

## 6. 정리

### 핵심 파일 맵

| 개념              | 파일 위치                                                                    |
| ----------------- | ---------------------------------------------------------------------------- |
| 엔티티 정의       | `metadata-models/src/main/resources/entity-registry.yml`                     |
| Aspect PDL 스키마 | `metadata-models/src/main/pegasus/com/linkedin/<domain>/`                    |
| Key Aspect        | `metadata-models/src/main/pegasus/com/linkedin/metadata/key/<Entity>Key.pdl` |
| URN 유틸리티      | `li-utils/src/main/javaPegasus/com/linkedin/common/urn/UrnUtils.java`        |
| EntityRegistry    | `entity-registry/src/main/java/.../models/registry/EntityRegistry.java`      |
| EntitySpec        | `entity-registry/src/main/java/.../models/EntitySpec.java`                   |
| AspectSpec        | `entity-registry/src/main/java/.../models/AspectSpec.java`                   |
| @Searchable 파싱  | `entity-registry/src/main/java/.../annotation/SearchableAnnotation.java`     |

### 멘탈 모델

```
DataHub 메타데이터 = 엔티티들의 집합

엔티티 (Entity)
  ├── URN (고유 식별자)
  ├── Key Aspect (URN을 결정하는 필드들)
  └── Aspects (모듈화된 메타데이터)
       ├── @Searchable 필드 → OpenSearch 인덱스
       ├── @Relationship 필드 → 그래프 DB
       └── 일반 필드 → MySQL/Postgres에만 저장
```

---

## 검증

이 튜토리얼의 학습을 검증하려면 다음 테스트를 실행하세요:

```bash
./docs/dev-guides/onboarding/tests/01-entity-aspect-model-test.sh
```
