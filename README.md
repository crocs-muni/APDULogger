# APDULogger
Logging setup for analysis of APDU commands exchanged between blackbox terminal and blackbox smartcard.

## Setup
This repository provides ControlService - Java application that can be built in NetBeans IDE.

The LogApplet can be modified in NetBeans IDE.

LogAppletCap provides libraries and build file for building the CAP file.

CAP file can be built via ant and special ant-javacard task, executing `ant -f LogAppletCap/build.xml` .

Resulted CAP file is located in the .upload directory and can be loaded into the card via `.upload/gp.exe -install ./upload/LogApplet.cap -default`.

## Usage
User needs smartcard with the LogApplet (replay card), built ControlService and two readers connected to the computer.

First, ATRs of both cards must be obtained, which can be done by connecting only one card and executing `.upload/gp.exe -vd -i`.
The ATRs must be hardcoded into the ControlService as String constant at the beginning of the file.
There does not need to be two known ATRs for each card, but when more, the better.

For logging the communication, the user has to connect the replay card to the target terminal.
An APDU from terminal is sent and logged. User then connect the replay card to the computer, where target card is also connected.
By running (`java -jar ControlService/dist/ControlService.jar`) the ControlService will take the log, communicates it to the target card, log the response and send the log back to the replay card.
This process is repeated.

Log can be printed be running ControlService as before but without connected target card.

Log on replay card can be reseted via `java -jar ControlService/dist/ControlService.jar -r`

The LogApplet can be deleted from the replay card via `.upload/gp.exe -delete 73696d706c666170706c6575`, where the number is LogApplet's ID, which can be changed in LogAppletCap/build.xml

Tested on Windows 10 with Java 1.8.

## Other resources
ant-javacard: https://github.com/martinpaljak/ant-javacard
GlobalPLatformPro: https://github.com/martinpaljak/GlobalPlatformPro
