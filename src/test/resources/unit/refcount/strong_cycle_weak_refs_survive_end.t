use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our ($WEAK_SCHEMA, $WEAK_STORAGE, $DESTROYED) = (undef, undef, 0);

{
    package EndStrongCycle::Schema;
    sub DESTROY { $main::DESTROYED++ }

    package EndStrongCycle::Storage;
    sub DESTROY { $main::DESTROYED++ }
}

sub create_cycle {
    my $schema = bless {}, 'EndStrongCycle::Schema';
    my $storage = bless { schema => $schema }, 'EndStrongCycle::Storage';
    $schema->{storage} = $storage;

    $WEAK_SCHEMA = $schema;
    weaken($WEAK_SCHEMA);
    $WEAK_STORAGE = $storage;
    weaken($WEAK_STORAGE);
}

create_cycle();

END {
    ok(defined $WEAK_SCHEMA, 'strong cycle keeps schema weak observer live through END');
    ok(defined $WEAK_STORAGE, 'strong cycle keeps storage weak observer live through END');
    is($DESTROYED, 0, 'strongly owned cycle is not destroyed before END callbacks');
    done_testing();
}
