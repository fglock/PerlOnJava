use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(weaken);

my $bless_override;
BEGIN {
    $bless_override = sub {
        return CORE::bless($_[0], @_ > 1 ? $_[1] : caller());
    };
    *CORE::GLOBAL::bless = sub { goto $bless_override };
}

use Try::Tiny;

my %weak_registry;

sub populate_weakregistry {
    my $target = shift;
    my $addr = "$target";

    for my $key (keys %weak_registry) {
        delete $weak_registry{$key} unless defined $weak_registry{$key}{weakref};
    }

    $weak_registry{$addr}{weakref} = $target
        unless defined $weak_registry{$addr}{weakref};
    weaken($weak_registry{$addr}{weakref});

    return $target;
}

$bless_override = sub {
    my $obj = CORE::bless($_[0], @_ > 1 ? $_[1] : caller());
    return $obj if ref($obj) =~ /^utf8/;
    return populate_weakregistry($obj);
};

for my $func (qw(try catch finally)) {
    no strict 'refs';
    no warnings 'redefine';
    my $orig = \&{"Try::Tiny::$func"};
    *{"Try::Tiny::$func"} = sub (&;@) {
        populate_weakregistry($_[0]);
        goto $orig;
    };
}

{
    package UnitDBICTry::Storage;

    sub new {
        my ($class, $schema) = @_;
        my $self = bless { schema => $schema }, $class;
        Scalar::Util::weaken($self->{schema});
        return $self;
    }

    sub schema { $_[0]->{schema} }

    sub dbh_do {
        my ($self, $cb) = @_;
        return $cb->($self);
    }

    package UnitDBICTry::Schema;

    sub new {
        my $class = shift;
        my $self = bless { resultsets => [] }, $class;
        $self->{storage} = UnitDBICTry::Storage->new($self);
        return $self;
    }

    sub storage { $_[0]->{storage} }

    sub resultset {
        my ($self, $name) = @_;
        die "schema backref was cleared" unless defined $self;
        return UnitDBICTry::ResultSet->new($self, $name);
    }

    sub txn_do {
        my ($self, $cb) = @_;
        return Try::Tiny::try {
            $cb->();
        }
        Try::Tiny::catch {
            die $_;
        };
    }

    sub DESTROY { $main::UNIT_DBIC_TRY_SCHEMA_DESTROYED++ }

    package UnitDBICTry::ResultSet;

    sub new {
        my ($class, $schema, $name) = @_;
        return bless { schema => $schema, name => $name }, $class;
    }

    sub create {
        my ($self, $attrs) = @_;
        return UnitDBICTry::Row->new($self->{schema}, $attrs);
    }

    sub page { return $_[0] }

    sub pager {
        my $self = shift;
        return UnitDBICTry::Pager->new($self->{schema});
    }

    sub new_result {
        my ($self, $attrs) = @_;
        return UnitDBICTry::Row->new($self->{schema}, $attrs);
    }

    package UnitDBICTry::Row;

    sub new {
        my ($class, $schema, $attrs) = @_;
        return bless { schema => $schema, attrs => $attrs || {} }, $class;
    }

    package UnitDBICTry::Pager;

    sub new {
        my ($class, $schema) = @_;
        return bless { schema => $schema }, $class;
    }

    sub total_entries {
        my ($self, $count) = @_;
        $self->{total_entries} = $count;
        return $self;
    }
}

$main::UNIT_DBIC_TRY_SCHEMA_DESTROYED = 0;

my $schema = UnitDBICTry::Schema->new;
my $storage = $schema->storage;

my ($row_obj, $pager, $pager_with_count) = $schema->txn_do(sub {
    my $artist = $schema->resultset("Artist")->create({ name => "foo artist" });
    my $pg = $schema->resultset("Artist")->page(2)->pager;
    my $pg_wcount = $schema->resultset("Artist")->page(4)->pager->total_entries(66);
    return ($artist, $pg, $pg_wcount);
});

ok defined $storage->schema,
    "weak storage schema backref survives Try::Tiny goto leak tracing";
is $main::UNIT_DBIC_TRY_SCHEMA_DESTROYED, 0,
    "schema DESTROY does not fire while outer schema lexical is live";

my $dbh_do_ok = eval {
    $storage->dbh_do(sub {
        my $schema_from_storage = $_[0]->schema;
        return $schema_from_storage->resultset("Artist")->new_result({});
    });
    1;
};

ok $dbh_do_ok,
    "storage callback can still call resultset through weak schema backref";
