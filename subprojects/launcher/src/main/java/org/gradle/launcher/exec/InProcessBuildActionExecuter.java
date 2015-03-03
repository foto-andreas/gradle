/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.launcher.exec;

import org.gradle.BuildResult;
import org.gradle.StartParameter;
import org.gradle.api.internal.GradleInternal;
import org.gradle.initialization.*;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.concurrent.Stoppable;

public class InProcessBuildActionExecuter implements BuildActionExecuter<BuildActionParameters> {
    private final GradleLauncherFactory gradleLauncherFactory;
    private final BuildActionRunner buildActionRunner;

    public InProcessBuildActionExecuter(GradleLauncherFactory gradleLauncherFactory, BuildActionRunner buildActionRunner) {
        this.gradleLauncherFactory = gradleLauncherFactory;
        this.buildActionRunner = buildActionRunner;
    }

    public Object execute(BuildAction action, BuildRequestContext buildRequestContext, BuildActionParameters actionParameters) {
        DefaultBuildController buildController = new DefaultBuildController(gradleLauncherFactory, buildRequestContext);
        try {
            return buildActionRunner.run(action, buildController);
        } finally {
            buildController.stop();
        }
    }

    private static class DefaultBuildController implements BuildController, Stoppable {
        private enum State { NotStarted, Created, Completed }
        private State state = State.NotStarted;
        private final GradleLauncherFactory gradleLauncherFactory;
        private final BuildRequestContext buildRequestContext;
        private DefaultGradleLauncher gradleLauncher;
        private StartParameter startParameter = new StartParameter();

        private DefaultBuildController(GradleLauncherFactory gradleLauncherFactory, BuildRequestContext buildRequestContext) {
            this.gradleLauncherFactory = gradleLauncherFactory;
            this.buildRequestContext = buildRequestContext;
        }

        public void setStartParameter(StartParameter startParameter) {
            if (state != State.NotStarted) {
                throw new IllegalStateException("Cannot change start parameter after build has started.");
            }
            this.startParameter = startParameter;
        }

        public DefaultGradleLauncher getLauncher() {
            if (state == State.Completed) {
                throw new IllegalStateException("Cannot use launcher after build has completed.");
            }
            if (state == State.NotStarted) {
                gradleLauncher = (DefaultGradleLauncher) gradleLauncherFactory.newInstance(startParameter, buildRequestContext);
                state = State.Created;
            }
            return gradleLauncher;
        }

        public GradleInternal getGradle() {
            return getLauncher().getGradle();
        }

        public GradleInternal run() {
            return check(getLauncher().run());
        }

        public GradleInternal configure() {
            return check(getLauncher().getBuildAnalysis());
        }

        private GradleInternal check(BuildResult buildResult) {
            state = State.Completed;
            if (buildResult.getFailure() != null) {
                throw new ReportedException(buildResult.getFailure());
            }
            return (GradleInternal) buildResult.getGradle();
        }

        public void stop() {
            CompositeStoppable.stoppable(gradleLauncher).stop();
        }
    }
}
