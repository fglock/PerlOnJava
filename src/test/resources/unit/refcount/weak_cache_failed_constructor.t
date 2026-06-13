use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package DB;

    sub capture_failed_constructor_frame {
        caller(1);
    }
}

{
    package WeakCacheFailedConstructor::Obj;
    use overload bool => sub { defined $_[0]->{id} }, fallback => 1;

    sub _attribute_store {
        my ($self, %data) = @_;
        @{$self}{keys %data} = values %data;
    }

    sub _deflate_for_create {
        my $self = shift;
        if (ref $self->{bad_relation}) {
            DB::capture_failed_constructor_frame();
            die "bad related object\n";
        }
    }
}

our %LIVE_OBJECTS;

sub _fresh_init {
    my ($class, $key, $data) = @_;
    my $obj = bless {}, $class;
    $obj->_attribute_store(%$data);
    weaken($LIVE_OBJECTS{$key} = $obj);
    return $obj;
}

sub _init {
    my ($class, $data) = @_;
    my $key = $data->{id};
    return $LIVE_OBJECTS{$key} || _fresh_init($class, $key, $data);
}

sub _insert {
    my ($class, $data) = @_;
    my $self = _init($class, $data);
    $self->_deflate_for_create;
    return $self;
}

eval {
    _insert(
        'WeakCacheFailedConstructor::Obj',
        {
            id           => 'same-primary-key',
            bad_relation => bless({}, 'WeakCacheFailedConstructor::WrongClass'),
        },
    );
};

like($@, qr/^bad related object/, 'failed create dies before returning object');
ok(!defined $LIVE_OBJECTS{'same-primary-key'},
    'weak live-object cache entry is cleared while unwinding failed create');

my $created = _insert(
    'WeakCacheFailedConstructor::Obj',
    {
        id            => 'same-primary-key',
        good_relation => 1,
    },
);

ok(defined $created, 'second create returns a fresh object');
ok(!exists $created->{bad_relation}, 'fresh object does not reuse failed create data');
is($created->{good_relation}, 1, 'fresh object stores new create data');

done_testing();
