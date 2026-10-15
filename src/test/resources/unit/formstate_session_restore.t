use strict;
use warnings;
use Test::More;

BEGIN {
    for my $module (
        qw(CGI CGI::Application CGI::Session
           CGI::Application::Plugin::Session
           CGI::Application::Plugin::FormState)
    ) {
        (my $file = "$module.pm") =~ s{::}{/}g;
        eval { require $file; 1 } or do {
            plan skip_all => "requires $module for the FormState integration regression";
            exit 0;
        };
    }
}

use File::Temp qw(tempdir);

our $session_dir = tempdir(CLEANUP => 1);
our ($session_id, $form_state_id, $restored);
$ENV{CGI_APP_RETURN_ONLY} = 1;

sub response_body {
    my ($response) = @_;
    $response =~ s/\A.*?\r?\n\r?\n//s;
    return $response;
}

{
    package FormStateFirstRequest;
    our @ISA = qw(CGI::Application);
    CGI::Application::Plugin::Session->import;
    CGI::Application::Plugin::FormState->import;

    sub setup {
        my ($self) = @_;
        $self->run_modes(['display']);
        $self->start_mode('display');
        $self->session_config(
            CGI_SESSION_OPTIONS => ['driver:File', undef,
                { Directory => $main::session_dir }],
        );
        $main::session_id = $self->session->id;
    }

    sub display {
        my ($self) = @_;
        $self->form_state->param(
            name       => 'Road Runner',
            occupation => 'Having Fun',
        );
        $main::form_state_id = $self->form_state->id;
        return $main::form_state_id;
    }
}

{
    package FormStateSecondRequest;
    our @ISA = qw(CGI::Application);
    CGI::Application::Plugin::Session->import;
    CGI::Application::Plugin::FormState->import;

    sub setup {
        my ($self) = @_;
        $self->run_modes(['restore']);
        $self->start_mode('restore');
        $self->session_config(
            CGI_SESSION_OPTIONS => ['driver:File', $main::session_id,
                { Directory => $main::session_dir }],
        );
    }

    sub restore {
        my ($self) = @_;
        my $state = $self->form_state;
        $main::restored = [
            $state->param('name'),
            $state->param('occupation'),
        ];
        return join '|', @$main::restored;
    }
}

my $first_response = response_body(FormStateFirstRequest->new->run);
is($first_response, $form_state_id,
    'first request exposes the token used to restore form state');

my $query = CGI->new;
$query->param(cap_form_state => $form_state_id);
my $second_response = response_body(FormStateSecondRequest->new(QUERY => $query)->run);

is($second_response, 'Road Runner|Having Fun',
    'second request restores saved values from the same session');
is_deeply($restored, ['Road Runner', 'Having Fun'],
    'restored state preserves each named form value');
isnt($session_id, '', 'both requests use a persistent session');

done_testing();
