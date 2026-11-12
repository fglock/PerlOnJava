use strict;
use warnings;
use Test::More tests => 5;
use Scalar::Util qw(weaken);
use Sub::Util ();
use Try::Tiny;

our %weak_callbacks;
our ($dbh_destroyed, $source_destroyed) = (0, 0);

for my $function (qw(try catch finally)) {
    no strict 'refs';
    no warnings 'redefine';
    my $original = \&{"Try::Tiny::$function"};
    *{"Try::Tiny::$function"} = sub (&;@) {
        if ($function eq 'try') {
            push @{$weak_callbacks{$function}}, $_[0];
            weaken($weak_callbacks{$function}[-1]);
        }
        goto $original;
    };
}

{
    package Unit::TryCallbackOwner::DBH;

    sub last_insert_id { $_[0]->{id} }
    sub DESTROY { $main::dbh_destroyed++ }

    package Unit::TryCallbackOwner::Source;

    sub name { $_[0]->{name} }
    sub DESTROY { $main::source_destroyed++ }

    package Unit::TryCallbackOwner::Storage;

    sub txn_do {
        my ($self, $callback) = @_;
        return Try::Tiny::try {
            $callback->();
        } Try::Tiny::catch {
            die $_;
        };
    }

    sub _dbh_last_insert_id {
        my ($self, $dbh, $source, $column) = @_;
        my $id = Try::Tiny::try {
            $dbh->last_insert_id(undef, undef, $source->name, $column);
        };
        return $id if defined $id;
    }
}

my ($weak_dbh, $weak_source);
my $id;
{
    my $dbh = bless { id => 42 }, 'Unit::TryCallbackOwner::DBH';
    my $source = bless { name => 'Artist' }, 'Unit::TryCallbackOwner::Source';
    weaken($weak_dbh = $dbh);
    weaken($weak_source = $source);

    ($id) = Unit::TryCallbackOwner::Storage->txn_do(sub {
        Unit::TryCallbackOwner::Storage->_dbh_last_insert_id(
            $dbh, $source, 'artist_id');
    });
}

is($id, 42, 'the nested Try::Tiny calls return the scalar last-insert-id result');
ok(!(grep { defined $_ } @{$weak_callbacks{try} || []}),
    'all Try::Tiny callback blocks are released after goto forwarding');
ok(!defined($weak_dbh), 'the temporary try callback releases its captured DBI handle');
ok(!defined($weak_source), 'the temporary try callback releases its captured result source');
is($dbh_destroyed + $source_destroyed, 2, 'both captured objects are destroyed exactly once');
