///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//SOURCES ../src/corebank/readers/RelationshipFileReader.java ../src/corebank/readers/TransactionFileReader.java
//SOURCES ../src/corebank/readers/FileReaderSupport.java ../src/corebank/readers/AccountFileReader.java
//SOURCES  ../src/corebank/readers/CustomerFileReader.java
//DEPS org.springframework.boot:spring-boot-dependencies:3.4.4@pom
//DEPS org.springframework.boot:spring-boot-starter
//DEPS org.springframework.batch:spring-batch-infrastructure
//DEPS org.junit.platform:junit-platform-console-standalone:1.11.4
//SOURCES ../src/corebank/BankingRecords.java ../src/corebank/readers/CodePointLineTokenizer.java ../src/corebank/readers/AccountFileReader.java
//SOURCES corebank/AdditionalFileReadersTest.java
//SOURCES corebank/CustomerFileReaderTest.java corebank/AccountFileReaderTest.java

import org.junit.platform.console.ConsoleLauncher;

public class ReaderTests {
    public static void main(String[] args) {
        ConsoleLauncher.main(
            "execute", "--select-class=corebank.CustomerFileReaderTest", "--select-class=corebank.AccountFileReaderTest", "--select-class=corebank.AdditionalFileReadersTest", "--fail-if-no-tests");
    }
}
