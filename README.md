## elasticserach-analysis-kuromoji-ipadic-neologd

- Elasticsearch 8.3
- Lucene 9.3.0

## ローカル環境でのbuild方法

Based on https://github.com/kazuhira-r/kuromoji-with-mecab-neologd-buildscript.

```sh
WORKDIR=$(pwd)
MAX_BASEFORM_LENGTH=15
INSTALL_ADJECTIVE_EXT=0

-- mecab-ipadic-neologdで辞書ファイルの作成
-- 成果物: mecab-ipadic-neologd/build/mecab-ipadic-2.7.0-20070801-neologd-20200910/* の作成
git clone git@github.com:neologd/mecab-ipadic-neologd.git
cd ${WORKDIR}/mecab-ipadic-neologd
git checkout master
git fetch origin
git reset --hard origin/master
libexec/make-mecab-ipadic-neologd.sh -L ${MAX_BASEFORM_LENGTH} -T ${INSTALL_ADJECTIVE_EXT}

-- lucene/analysis/kuromoji 用のgenerated fileを一時的に作成(後にoverrideされるもの)
-- 成果物: lucene/analysis/kuromoji/build/generate/mecab-ipadic-2.7.0-20070801 directoryの作成
cd ${WORKDIR}
git clone git@github.com:apache/lucene.git
cd ${WORKDIR}/lucene
git checkout releases/lucene/9.3.0
./gradlew -p lucene/analysis/kuromoji regenerate

-- mecab-ipadic-neologdの辞書ファイルをコピー
cp -R ../mecab-ipadic-neologd/build/mecab-ipadic-2.7.0-20070801-neologd-20200910/* lucene/analysis/kuromoji/build/generate/mecab-ipadic-2.7.0-20070801

-- lucene/analysis/kuromojiのbuild (コピーされたmecab-ipadic-neologdを含むもの)
-- 成果物: lucene/analysis/kuromoji/build/libs/lucene-analysis-kuromoji-10.0.0-SNAPSHOT.jar
./gradlew -p lucene/analysis/common assemble
./gradlew -p lucene/analysis/kuromoji assemble

-- jarをmvnから利用できるようにするために登録 (既存のシステムに載るため、groupId等はorg.codelibsを流用)
mvn install:install-file -Dfile=lucene/analysis/kuromoji/build/libs/lucene-analysis-kuromoji-10.0.0-SNAPSHOT.jar -DgroupId=org.codelibs -DartifactId=lucene-analyzers-kuromoji-ipadic-neologd -Dversion=9.3.0-20200910 -Dpackaging=jar
mvn install:install-file -Dfile=lucene/analysis/common/build/libs/lucene-analysis-common-10.0.0-SNAPSHOT.jar -DgroupId=org.codelibs -DartifactId=lucene-analyzers-common-ipadic-neologd -Dversion=9.3.0-20200910 -Dpackaging=jar

-- elasticsearch-analysis-kuromoji-ipadic-neologd (本repository)
-- 成果物: elasticsearch-analysis-kuromoji-ipadic-neologd/target/releases/elasticsearch-analysis-kuromoji-ipadic-neologd-7.2.1-SNAPSHOT.zip
mvn package -Dmaven.test.skip
```

## インストール方法

```sh
> ./bin/elasticsearch-plugin install --batch file://path/to/elasticsearch-analysis-kuromoji-ipadic-neologd-7.2.1-SNAPSHOT.zip
> ./bin/elasticsearch-plugin list
analysis-kuromoji-ipadic-neologd
```
