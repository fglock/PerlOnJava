use strict;
use warnings;

print "1..8\n";

my $test_number = 0;

sub ok {
    my ($pass, $name) = @_;
    $test_number++;
    print($pass ? "ok " : "not ok ", $test_number, " - ", $name, "\n");
}

sub is {
    my ($got, $expected, $name) = @_;
    ok(defined($got) && defined($expected) ? $got eq $expected : !defined($got) && !defined($expected), $name);
}

my $field = join q(), reverse map { chr($_) } 0..255;
my $quoted = q{"} . quotemeta($field) . q{"};

my $body = sprintf <<'EOS', q(simple), $quoted, q(simple), $quoted;
@_ > 1
  ? shift->set_%s(%s, @_)
  : shift->get_%s(%s)
EOS

my $src = "sub { my \$dummy; sub { \$dummy if 0; $body } }";
my $factory = eval $src;
die $@ if $@;

{
    package EvalStringSourceTypes::Object;

    sub set_simple {
        $_[0]->{$_[1]} = $_[2];
    }

    sub get_simple {
        $_[0]->{$_[1]};
    }
}

my $obj = bless {}, 'EvalStringSourceTypes::Object';
my $accessor = $factory->();

is($accessor->($obj, 'a'), 'a', 'eval-generated accessor setter returns value');
is($accessor->($obj), 'a', 'eval-generated accessor getter returns stored value');
is($obj->{$field}, 'a', 'high-byte eval string literal is the expected hash key');

my ($stored_key) = keys %$obj;
is(length($stored_key), 256, 'stored high-byte key keeps character length');
is(ord(substr($stored_key, 0, 1)), 255, 'stored high-byte key starts at chr 255');
is(ord(substr($stored_key, -1)), 0, 'stored high-byte key ends at chr 0');
ok($stored_key eq $field, 'stored high-byte key equals original field');

{
    package EvalStringSourceTypes::Goto;

    our $target = sub {
        return 'ok';
    };

    eval q{sub bounce { goto $target }; 1} or die $@;
}

is(EvalStringSourceTypes::Goto::bounce(), 'ok', 'eval named sub resolves goto package global coderef');
