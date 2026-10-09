use strict;
use warnings;
use Test::More tests => 1;
use Scalar::Util qw(weaken);

my @warnings;
my $source;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    {
        package ScopedSchemaLike;
        sub new {
            my ($class, $source) = @_;
            my $self = bless { source => $source }, $class;
            $source->{schema} = $self;
            Scalar::Util::weaken($source->{schema});
            return $self;
        }
        sub DESTROY {
            my ($self) = @_;
            my $source = $self->{source};
            if ($source && $source->{shared}) {
                $source->{schema} = $self;
                Scalar::Util::weaken($self->{source});
            }
        }

        package ScopedSourceLike;
        sub new { bless {}, shift }
    }

    $source = ScopedSourceLike->new;
    $source->{shared} = 1;
    {
        my $schema = ScopedSchemaLike->new($source);
    }
    undef $source->{shared};
    undef $source;
}

is(scalar @warnings, 0,
    'scope cleanup can retain a schema through its source without a global-destruction warning');
