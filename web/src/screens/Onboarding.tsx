import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { friendlyError } from '../data/api';
import { useData, usePaths, useUser } from '../data/DataProvider';
import { Button } from '../components/Button';
import { Field, Input } from '../components/Field';
import { Icon } from '../components/Icon';

/** Step 1 of setup: name the family and the parent. Calls createFamily. */
export function OnboardingScreen() {
  const { api } = useData();
  const user = useUser();
  const paths = usePaths();
  const navigate = useNavigate();
  const [familyName, setFamilyName] = useState(() => {
    const last = user.displayName.trim().split(/\s+/).slice(-1)[0];
    return last && user.displayName.trim().includes(' ') ? `The ${last} family` : '';
  });
  const [parentName, setParentName] = useState('');
  const [phone, setPhone] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const next: Record<string, string> = {};
    if (!familyName.trim()) next.familyName = 'Give your family a name, like “The Riveras”.';
    if (!parentName.trim()) next.parentName = 'What do you call them? “Mom” works well.';
    if (phone.trim() && phone.replace(/\D/g, '').length < 7) next.phone = 'That number looks too short.';
    setErrors(next);
    if (Object.keys(next).length) return;
    setBusy(true);
    setFormError(null);
    try {
      const { familyId, deviceId } = await api.createFamily({
        familyName: familyName.trim(),
        parentName: parentName.trim(),
        ...(phone.trim() ? { helperPhone: phone.trim() } : {}),
      });
      navigate(paths.setup(familyId, deviceId));
    } catch (err) {
      setFormError(friendlyError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page page-narrow">
      <ol className="steps" aria-label="Setup steps">
        <li className="is-current" aria-current="step">
          <span>1</span> Your family
        </li>
        <li>
          <span>2</span> Connect the phone
        </li>
      </ol>
      <header className="page-header">
        <h1 className="h-page">Welcome, {user.displayName.split(' ')[0]}.</h1>
        <p className="lede">Let’s set up your family. It takes about a minute here, then ten minutes sitting with your parent.</p>
      </header>

      <form className="card card-padded stack" style={{ ['--gap' as string]: 'var(--s-5)' }} onSubmit={submit} noValidate>
        <Field label="Family name" hint="Only your family sees this." error={errors.familyName}>
          {(ids) => <Input {...ids} name="familyName" autoComplete="off" placeholder="The Rivera family" value={familyName} onChange={(e) => setFamilyName(e.target.value)} />}
        </Field>
        <Field label="What do you call your parent?" hint="We’ll use this everywhere, like “Mom’s phone”." error={errors.parentName}>
          {(ids) => <Input {...ids} name="parentName" autoComplete="off" placeholder="Mom" value={parentName} onChange={(e) => setParentName(e.target.value)} />}
        </Field>
        <Field label="Your phone number" optional hint={`So “Call ${parentName.trim() || 'you'} first” buttons on their phone reach you.`} error={errors.phone}>
          {(ids) => <Input {...ids} name="phone" type="tel" inputMode="tel" autoComplete="tel" placeholder="+1 555 010 1111" value={phone} onChange={(e) => setPhone(e.target.value)} />}
        </Field>
        {formError ? (
          <p className="field-error" role="alert">
            {formError}
          </p>
        ) : null}
        <Button type="submit" variant="primary" size="lg" block loading={busy} iconAfter="arrowRight">
          Create our family
        </Button>
      </form>

      <p className="small subtle center-text">
        <Icon name="lock" size={16} className="inline-icon" /> Nothing is shared until your parent says yes on their phone.
      </p>
    </div>
  );
}
