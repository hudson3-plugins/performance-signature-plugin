/*
 * Copyright (c) 2014 T-Systems Multimedia Solutions GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.tsystems.mms.apm.performancesignature.model;

import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.List;

public class UnitTestCase extends ConfigurationTestCase {

    @DataBoundConstructor
    public UnitTestCase(final String name, final List<Dashboard> singleDashboards, final List<Dashboard> comparisonDashboards,
                        final String xmlDashboard, final String clientDashboard) {
        super(name, singleDashboards, comparisonDashboards, xmlDashboard, clientDashboard);
    }

    @Extension
    public static final class DescriptorImpl extends ConfigurationTestCaseDescriptor {
        @Override
        public String getDisplayName() {
            return "UnitTest test case";
        }
    }

}
