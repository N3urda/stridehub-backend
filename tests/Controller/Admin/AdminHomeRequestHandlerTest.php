<?php

namespace App\Tests\Controller\Admin;

final class AdminHomeRequestHandlerTest extends AdminWebTestCase
{
    public function testRootOpensTheProtectedOverview(): void
    {
        $this->client->request('GET', '/');
        $this->assertResponseRedirects('/admin/insights');

        $this->client->followRedirect();
        $this->assertResponseRedirects('/admin/login');
    }

    public function testAuthenticatedAdminEntryOpensTheOverview(): void
    {
        $this->client->loginUser($this->adminUser());
        $this->client->request('GET', '/admin');

        $this->assertResponseRedirects('/admin/insights');
    }
}
