use strict;
use warnings;
use Test::More tests => 3;

{
    package LocalAtStringException;

    use overload
        bool => sub { 1 },
        q{""} => sub {
            my $self = shift;
            my $deparse = do {
                local $@;
                require B::Deparse;
                B::Deparse->new;
            };
            my $code = $deparse->coderef2text(sub { });
            return $self->{message};
        };

    sub new {
        bless { message => "object failure" }, shift;
    }
}

my $ok = eval {
    die LocalAtStringException->new;
    1;
};

my $error = $@;

ok(!$ok, 'eval catches object die');
isa_ok($error, 'LocalAtStringException');
is("$error", 'object failure', 'object die payload survives overload stringification with local $@');
