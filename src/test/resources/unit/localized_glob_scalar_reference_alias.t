use strict;
use warnings;
use Test::More tests => 4;
use Moose;

{
    package Issue1939::MooseObject;
    use Moose;
    has filename => (is => 'ro', default => 'reference survives');
}

our @destroyed;
{
    package Issue1939::LocalizedValue;
    sub DESTROY { push @main::destroyed, $_[0]->{id} }
}

sub install_hash {
    my ($hash, $destination) = @_;
    for my $name (keys %$hash) {
        my $value = $hash->{$name};

        # This is the relevant part of Text::Template::_install_hash:
        # reference values assign through a localized whole-glob alias.
        no strict 'refs';
        no warnings 'redefine';
        local *SYM = *{"${destination}::$name"};
        if (ref $value) {
            *SYM = $value;
        } else {
            *SYM = \$value;
        }
    }
}

my $self = Issue1939::MooseObject->new;
my $destination = 'Issue1939::GeneratedTemplate';
install_hash({ self => \$self }, $destination);
my $rendered = eval 'package ' . $destination . '; $self->filename';
is($@, '', 'generated template can call a method on the installed reference');
is($rendered, 'reference survives', 'the localized glob alias preserves the Moose object');
is(ref($self), 'Issue1939::MooseObject', 'the source lexical remains the original Moose object');

{
    local *Issue1939::LocalizedValue::slot;
    $Issue1939::LocalizedValue::slot = bless { id => 'temporary' }, 'Issue1939::LocalizedValue';
}
is_deeply(\@destroyed, ['temporary'],
    'a Moose-style DESTROY still runs for a value owned only by a localized glob');
