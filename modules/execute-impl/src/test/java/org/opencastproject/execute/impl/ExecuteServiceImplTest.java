/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.execute.impl;

import org.opencastproject.execute.api.ExecuteException;
import org.opencastproject.execute.api.ExecuteService;
import org.opencastproject.job.api.Job;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementBuilderFactory;
import org.opencastproject.mediapackage.MediaPackageElementParser;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.workspace.api.Workspace;

import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Test suite for the Execute Service
 */
public class ExecuteServiceImplTest {

  @SuppressWarnings("unused")
  private static final Logger logger = LoggerFactory.getLogger(ExecuteServiceImplTest.class);

  private static ExecuteServiceImpl executor;
  private static final String TEXT = "En un lugar de la Mancha de cuyo nombre no quiero acordarme...";
  private static String pattern;
  private static URI baseDirURI;
  private static File baseDir;

  private static BundleContext bundleContext;
  private static ComponentContext cc;
  private static String configKey1;
  private static String configKey2;

  @BeforeClass
  public static void prepareTest() throws URISyntaxException, NotFoundException, IOException {
    // Get the base directory
    baseDirURI = ExecuteServiceImplTest.class.getResource("/").toURI();
    baseDir = new File(baseDirURI);

    // Set up mock context
    configKey1 = "edu.harvard.dce.param1";
    String configValue1 = baseDir.getAbsolutePath() + "/test.txt";
    bundleContext = EasyMock.createNiceMock(BundleContext.class);
    EasyMock.expect(bundleContext.getProperty(configKey1)).andReturn(configValue1).anyTimes();
    EasyMock.replay(bundleContext);
    cc = EasyMock.createNiceMock(ComponentContext.class);
    EasyMock.expect(cc.getBundleContext()).andReturn(bundleContext).anyTimes();
    configKey2 = "edu.harvard.dce.param2";
    String configValue2 = baseDir.getAbsolutePath() + "/test.txt";
    Hashtable<String, Object> props = new Hashtable<>();
    props.put(configKey2, configValue2);
    EasyMock.expect(cc.getProperties()).andReturn(props);
    EasyMock.replay(cc);

    // Create the executor service
    executor = new ExecuteServiceImpl();
    executor.activate(cc);

    // Create a mock workspace
    Workspace workspace = EasyMock.createNiceMock(Workspace.class);
    EasyMock.expect(workspace.get(baseDirURI)).andReturn(baseDir).anyTimes();
    EasyMock.replay(workspace);
    executor.setWorkspace(workspace);

    // Set up the text pattern to test
    pattern = String.format("The specified track (%s) is in the following location: %s",
            ExecuteService.INPUT_FILE_PATTERN, ExecuteService.INPUT_FILE_PATTERN);
  }

  @Test
  public void testNoElements() throws ExecuteException, NotFoundException {
    List<String> params = new ArrayList<>();
    params.add("echo");
    params.add(TEXT);

    try {
      executor.doProcess(params, (MediaPackageElement) null, null, null);
      Assert.fail("The input element should never be null");
    } catch (NullPointerException e) {
      // This exception is expected
    }
  }

  @Test
  public void testWithInputElement() throws ExecuteException, NotFoundException {
    List<String> params = new ArrayList<>();
    params.add("echo");
    params.add(pattern);
    MediaPackageElement element = MediaPackageElementBuilderFactory.newInstance().newElementBuilder()
            .elementFromURI(baseDirURI);

    String result = executor.doProcess(params, element, null, null);

    Assert.assertEquals(result, "");
  }

  @Test
  public void testWithGlobalConfigParam() throws ExecuteException, NotFoundException {
    List<String> params = new ArrayList<>();
    params.add("cat");
    params.add("#{" + configKey1 + "}");
    MediaPackage mp = null;

    String result = executor.doProcess(params, mp, null, null);

    // If it doesn't get a file not found, it is ok
    Assert.assertEquals(result, "");
  }

  @Test
  public void testWithServiceConfigParam() throws ExecuteException, NotFoundException {
    List<String> params = new ArrayList<>();
    params.add("cat");
    params.add("#{" + configKey2 + "}");
    MediaPackage mp = null;

    String result = executor.doProcess(params, mp, null, null);

    // If it doesn't get a file not found, it is ok
    Assert.assertEquals(result, "");
  }

  @Test
  public void testWithJobIdParam() throws Exception {
    long jobId = 12345L;
    MediaPackageElement element = MediaPackageElementBuilderFactory.newInstance().newElementBuilder()
            .elementFromURI(baseDirURI);

    // Build arguments for Execute_Element with #{job_id} in the params.
    // After substitution, #{job_id} becomes "12345" and the command runs as: echo 12345
    List<String> args = new ArrayList<>();
    args.add("echo");
    args.add(ExecuteService.JOB_ID_PATTERN);
    args.add(MediaPackageElementParser.getAsXml(element));

    Job job = EasyMock.createNiceMock(Job.class);
    EasyMock.expect(job.getId()).andReturn(jobId).anyTimes();
    EasyMock.expect(job.getOperation()).andReturn(ExecuteServiceImpl.Operation.Execute_Element.toString()).anyTimes();
    EasyMock.expect(job.getArguments()).andReturn(args).anyTimes();
    EasyMock.replay(job);

    executor.allowedCommands.add("*");
    try {
      String result = executor.process(job);
      Assert.assertEquals("", result);
    } finally {
      executor.allowedCommands.clear();
    }
  }
}
