use strict;
use warnings;
use Test::More;

{
    package Local::Response;

    sub new {
        my ($class) = @_;
        return bless { status => undef, headers => [] }, $class;
    }

    sub _status {
        return sub {
            my ($self, @args) = @_;
            $self->{status} = \@args;
        };
    }

    sub status { shift->_status->(@_) }

    sub _header {
        return sub {
            my ($self, @args) = @_;
            push @{ $self->{headers} }, \@args;
        };
    }

    sub header { shift->_header->(@_) }
}

my $response = Local::Response->new;
$response->status(400, 'Bad Request');
$response->header('Content-Type', 'text/plain');

is_deeply($response->{status}, [400, 'Bad Request'],
    'method-returned coderef receives the original object and explicit status args');
is_deeply($response->{headers}, [['Content-Type', 'text/plain']],
    'method-returned coderef receives the original object and explicit header args');

done_testing;
