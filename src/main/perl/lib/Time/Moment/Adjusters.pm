package Time::Moment::Adjusters;
use strict;
use warnings;
use Carp qw[];

our $VERSION = '0.46';
our @EXPORT_OK = qw[NextDayOfWeek NextOrSameDayOfWeek PreviousDayOfWeek PreviousOrSameDayOfWeek NearestDayOfWeek FirstDayOfWeekInMonth LastDayOfWeekInMonth NthDayOfWeekInMonth WesternEasterSunday OrthodoxEasterSunday NearestMinuteInterval];
our %EXPORT_TAGS = (all => [@EXPORT_OK]);
require Exporter;
*import = \&Exporter::import;

sub _day { my ($day) = @_; ($day >= 1 && $day <= 7) or Carp::croak(q<Parameter 'day' is out of the range [1, 7]>); $day }
sub NextDayOfWeek { @_ == 1 or Carp::croak(q<Usage: NextDayOfWeek(day)>); my $d=_day($_[0]); sub { $_[0]->plus_days(($d-$_[0]->day_of_week+6)%7+1) } }
sub NextOrSameDayOfWeek { @_ == 1 or Carp::croak(q<Usage: NextOrSameDayOfWeek(day)>); my $d=_day($_[0]); sub { $_[0]->plus_days(($d-$_[0]->day_of_week)%7) } }
sub PreviousDayOfWeek { @_ == 1 or Carp::croak(q<Usage: PreviousDayOfWeek(day)>); my $d=_day($_[0]); sub { $_[0]->minus_days(($_[0]->day_of_week-$d+6)%7+1) } }
sub PreviousOrSameDayOfWeek { @_ == 1 or Carp::croak(q<Usage: PreviousOrSameDayOfWeek(day)>); my $d=_day($_[0]); sub { $_[0]->minus_days(($_[0]->day_of_week-$d)%7) } }
sub NearestDayOfWeek { @_ == 1 or Carp::croak(q<Usage: NearestDayOfWeek(day)>); my $d=_day($_[0]); sub { $_[0]->plus_days((($d-$_[0]->day_of_week+3)%7)-3) } }
sub FirstDayOfWeekInMonth { @_ == 1 or Carp::croak(q<Usage: FirstDayOfWeekInMonth(day)>); my $d=_day($_[0]); sub { my $t=$_[0]->with_day_of_month(1); $t->plus_days(($d-$t->day_of_week)%7) } }
sub LastDayOfWeekInMonth { @_ == 1 or Carp::croak(q<Usage: LastDayOfWeekInMonth(day)>); my $d=_day($_[0]); sub { my $t=$_[0]->at_last_day_of_month; $t->minus_days(($t->day_of_week-$d)%7) } }
sub NthDayOfWeekInMonth {
    @_ == 2 or Carp::croak(q<Usage: NthDayOfWeekInMonth(ordinal, day)>);
    my ($o,$d)=@_; ($o >= -4 && $o <= 4 && $o) or Carp::croak(q<Parameter 'ordinal' is out of the range [-4, -1] u [1, 4]>); _day($d);
    return $o>0 ? sub { my $t=$_[0]->with_day_of_month(1); $t->plus_days(7*($o-1)+($d-$t->day_of_week)%7) }
                : sub { my $t=$_[0]->at_last_day_of_month; $t->plus_days(7*($o+1)-($t->day_of_week-$d)%7) };
}
sub _western_easter {
    my ($year)=@_; my $a=$year%19; my $b=int($year/100); my $c=$year%100; my $d=int($b/4); my $e=$b%4; my $f=int(($b+8)/25); my $g=int(($b-$f+1)/3); my $h=(19*$a+$b-$d-$g+15)%30; my $i=int($c/4); my $k=$c%4; my $l=(32+2*$e+2*$i-$h-$k)%7; my $m=int(($a+11*$h+22*$l)/451); return (int(($h+$l-7*$m+114)/31), ($h+$l-7*$m+114)%31+1);
}
sub WesternEasterSunday { @_ == 0 or Carp::croak(q<Usage: WesternEasterSunday()>); sub { my ($m,$d)=_western_easter($_[0]->year); $_[0]->with_month($m)->with_day_of_month($d) } }
sub OrthodoxEasterSunday {
    @_ == 0 or Carp::croak(q<Usage: OrthodoxEasterSunday()>);
    return sub {
        my ($tm) = @_;
        my $year = $tm->year;
        # Upstream XS uses the Julian computus then translates the March day
        # into the proleptic Gregorian calendar.
        my $a = ($year % 19 * 19 + 15) % 30;
        my $julian_day = 28 + $a - ((int($year * 5 / 4) + $a) % 7);
        my $days_after_march = $julian_day + int($year / 100)
            - int($year / 400) - 3;
        return $tm->with_month(3)->with_day_of_month(1)->plus_days($days_after_march);
    };
}
sub NearestMinuteInterval { @_ == 1 or Carp::croak(q<Usage: NearestMinuteInterval(interval)>); my $i=$_[0]; ($i>=1 && $i<=1440) or Carp::croak(q<Parameter 'interval' is out of the range [1, 1440]>); my $msec=$i*60000; my $mid=int(($msec+1)/2); sub { $_[0]->with_millisecond_of_day($msec*int(($_[0]->millisecond_of_day+$mid)/$msec)) } }
1;
