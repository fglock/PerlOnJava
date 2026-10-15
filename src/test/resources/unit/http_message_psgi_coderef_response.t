use strict;
use warnings;
use Test::More;

BEGIN {
    for my $module (qw(HTTP::Message::PSGI HTTP::Response)) {
        (my $file = "$module.pm") =~ s{::}{/}g;
        eval { require $file; 1 } or do {
            plan skip_all => "requires $module for the PSGI response regression";
            exit 0;
        };
    }
}

my $callback_calls = 0;
my $response = HTTP::Response->from_psgi(sub {
    my ($respond) = @_;
    $callback_calls++;
    $respond->([403, ['Content-Type' => 'text/plain'], ['Forbidden']]);
});

isa_ok($response, 'HTTP::Response', 'callback response is converted');
is($callback_calls, 1, 'PSGI responder callback runs once');
is($response->code, 403, 'HTTP exception status is retained');
is($response->header('Content-Type'), 'text/plain', 'response headers are retained');
is($response->content, 'Forbidden', 'response body is retained');

my $streamed = HTTP::Response->from_psgi(sub {
    my ($respond) = @_;
    my $writer = $respond->([422, ['Content-Type' => 'text/plain'], undef]);
    $writer->write('Unprocessable ');
    $writer->write('Entity');
    $writer->close;
});

isa_ok($streamed, 'HTTP::Response', 'streaming callback response is converted');
is($streamed->code, 422, 'streamed response status is retained');
is($streamed->content, 'Unprocessable Entity',
    'streaming callback body chunks are preserved in order');

done_testing();
