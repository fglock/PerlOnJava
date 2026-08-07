package CatalystNetty::Controller::Root;

use strict;
use warnings;
use utf8;
use base 'Catalyst::Controller';

__PACKAGE__->config(namespace => '');

sub index :Path('/') :Args(0) {
    my ($self, $c) = @_;
    $c->res->body('index');
}

sub local :Local {
    my ($self, $c) = @_;
    $c->res->body('local');
}

sub path :Path('/path') :Args(1) {
    my ($self, $c, $arg) = @_;
    $c->res->body("path:$arg");
}

sub base :Chained('/') :PathPart('api') :CaptureArgs(0) {
    my ($self, $c) = @_;
    $c->stash->{chain} = 'base';
}

sub item :Chained('base') :PathPart('item') :Args(1) {
    my ($self, $c, $arg) = @_;
    $c->res->body(join(':', $c->stash->{chain}, 'item', $arg));
}

sub private :Private {
    my ($self, $c) = @_;
    $c->res->body('private');
}

sub invoke_private :Path('/invoke-private') :Args(0) {
    my ($self, $c) = @_;
    $c->forward('private');
}

sub params :Path('/params') :Args(0) {
    my ($self, $c) = @_;
    my $query = $c->req->query_parameters->{query} // '';
    my $form  = $c->req->body_parameters->{form} // '';
    $c->res->body("query=$query;form=$form");
}

sub upload :Path('/upload') :Args(0) {
    my ($self, $c) = @_;
    my $upload = $c->req->upload('file');
    my $body = $upload ? $upload->slurp : '';
    $c->res->body('upload=' . $body);
}

sub response :Path('/response') :Args(0) {
    my ($self, $c) = @_;
    $c->res->status(201);
    $c->res->header('X-Catalyst-Netty' => 'accepted');
    $c->res->cookies->{catalyst_netty} = {
        value => 'yes',
        path  => '/',
    };
    $c->res->body('response');
}

sub redirect :Path('/redirect') :Args(0) {
    my ($self, $c) = @_;
    $c->res->redirect($c->uri_for('/'));
}

sub utf8 :Path('/utf8') :Args(0) {
    my ($self, $c) = @_;
    $c->res->content_type('text/plain; charset=utf-8');
    $c->res->body('catalyst ☺');
}

sub failure :Path('/failure') :Args(0) {
    die "catalyst acceptance failure\n";
}

sub logging :Path('/logging') :Args(0) {
    my ($self, $c) = @_;
    $c->log->warn('catalyst-netty-acceptance-marker');
    $c->res->body('logged');
}

sub psgi :Path('/psgi') :Args(0) {
    my ($self, $c) = @_;
    my $env = $c->req->env;
    $c->res->body(join(';', map {
        $_ . '=' . ($env->{$_} ? 1 : 0)
    } qw/psgi.multithread psgi.multiprocess psgi.run_once/));
}

1;
