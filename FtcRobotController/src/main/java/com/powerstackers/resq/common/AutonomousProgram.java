/*
 * Copyright (C) 2015 Powerstackers
 *
 * Code to run our 2015-16 robot.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.powerstackers.resq.common;

import com.powerstackers.resq.common.enums.PublicEnums.DoorSetting;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

import static com.powerstackers.resq.common.enums.PublicEnums.AllianceColor;
import static com.powerstackers.resq.common.enums.PublicEnums.AllianceColor.BLUE;
import static com.powerstackers.resq.common.enums.PublicEnums.AllianceColor.RED;
import static com.powerstackers.resq.common.enums.PublicEnums.MotorSetting;

/**
 * @author Jonathan Thomas
 */
public class AutonomousProgram extends LinearOpMode {

    AllianceColor allianceColor;
    RobotAuto robot;

//    public AutonomousProgram(){}

    public AutonomousProgram(AllianceColor allianceColor) {
        this.allianceColor = allianceColor;
    }

    /**
     * Run the actual program.
     */
    @Override
    public void runOpMode() throws InterruptedException {
        // Initialize any sensors and servos
        robot = new RobotAuto(this);
        robot.initializeRobot();
//        robot.calibrateGyro();
        // Wait for the start of the match!Thread.interrupted()
        this.waitForStart();

        if (allianceColor == RED) {
            robot.setChurroGrabbers(DoorSetting.CLOSE);
            robot.calibrateGyro();
            sleep(5000);
            robot.setBrush(MotorSetting.FORWARD);
            robot.algorithm.goTicks(robot.algorithm.inchesToTicks(-119),0.8);
//            robot.algorithm.goTicks(robot.algorithm.inchesToTicks(68), -0.4);
            robot.algorithm.turnDegreesRight(88, 0.8);
            robot.setBrush(MotorSetting.STOP);
            robot.algorithm.goTicks(robot.algorithm.inchesToTicks(-14), 0.70);
//            robot.algorithm.goTicks(robot.algorithm.inchesToTicks(22), 0.4);
            robot.setClimberFlipper(DoorSetting.OPEN);

        } else if (allianceColor == BLUE) {
            robot.setChurroGrabbers(DoorSetting.CLOSE);
            robot.calibrateGyro();
            sleep(5000);
            sleep(10000);
            robot.setBrush(MotorSetting.FORWARD);
            robot.algorithm.goTicks(robot.algorithm.inchesToTicks(-124),0.8);
            robot.setBrush(MotorSetting.STOP);
//            robot.calibrateGyro();
//            sleep(5000);
            robot.algorithm.turnDegreesRight(88, 0.85);
//            robot.algorithm.turnDegrees(90, 0.8);
//            robot.setBrush(MotorSetting.REVERSE);
            robot.algorithm.goTicks(robot.algorithm.inchesToTicks(-14), 0.70);
            sleep(1000);
            robot.setBrush(MotorSetting.STOP);
            robot.setClimberFlipper(DoorSetting.OPEN);
                    } else {
            telemetry.addData("choosered", "deprecated: ");
            stop();
        }


        // Run any actions we desire
//        robot.tapBeacon(allianceColor);
    }
}