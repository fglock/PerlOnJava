use strict;
use warnings;
use Test::More tests => 2;

{
    package Issue1311AutoLoader;

    sub install_autoloaded_method {
        my $code = q{
            sub local_day_of_week {
                my $self = shift;
                return 1 + (($self->day_of_week - $self->{locale}->first_day_of_week) % 7);
            }
        };
        no overloading;
        eval $code;
        die $@ if $@;
    }

    sub new {
        my ($class, $day_of_week, $first_day_of_week) = @_;
        return bless {
            day_of_week => $day_of_week,
            locale => bless({ first_day_of_week => $first_day_of_week }, 'Issue1311AutoLoader::Locale'),
        }, $class;
    }

    sub day_of_week {
        return $_[0]->{day_of_week};
    }

    package Issue1311AutoLoader::Locale;

    sub first_day_of_week {
        return $_[0]->{first_day_of_week};
    }
}

my $date = Issue1311AutoLoader->new(2, 7);
Issue1311AutoLoader::install_autoloaded_method();

is($date->day_of_week - $date->{locale}->first_day_of_week, -5,
   'fixture has a negative weekday offset');
is($date->local_day_of_week, 3,
   'method compiled by AUTOLOAD under no overloading uses Perl modulus semantics');
