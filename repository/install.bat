mvn install:install-file -DlocalRepositoryPath="./" -Dfile="../../JFaceUtils/target/jface-utils-%1.jar" -Dsources="../../JFaceUtils/target/jface-utils-%1-sources.jar" -DgroupId=it.albertus -DartifactId=jface-utils -Dpackaging=jar -Dversion=%1