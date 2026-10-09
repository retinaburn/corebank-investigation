///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//SOURCES corebank/RelationshipFileReader.java corebank/TransactionFileReader.java
//SOURCES corebank/FileReaderSupport.java corebank/AccountFileReader.java
//DEPS org.springframework.boot:spring-boot-dependencies:3.4.4@pom
//DEPS org.springframework.boot:spring-boot-starter
//DEPS org.projectlombok:lombok
//DEPS org.springframework.batch:spring-batch-infrastructure
//SOURCES corebank/CoreApplication.java corebank/Config.java corebank/BankingRecords.java
//SOURCES corebank/CodePointLineTokenizer.java corebank/CustomerFileReader.java
//FILES application.yaml=../application.yaml

import corebank.CoreApplication;

/** JBang entry point; Spring components live in the corebank package. */
public class Core {
    public static void main(String[] args) {
        CoreApplication.main(args);
    }
}
